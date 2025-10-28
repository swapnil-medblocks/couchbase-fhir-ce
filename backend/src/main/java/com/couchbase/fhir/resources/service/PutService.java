package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.common.fhir.FhirMetaHelper;
import com.couchbase.fhir.resources.gateway.CouchbaseGateway;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for handling FHIR PUT operations (create or update resources with client-controlled IDs).
 * PUT operations always use the client-supplied ID and handle proper FHIR versioning.
 * This service handles both standalone transactions and nested transactions (from Bundle).
 */
@Service
public class PutService {
    
    private static final Logger logger = LoggerFactory.getLogger(PutService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    private static final String VERSIONS_COLLECTION = "Versions";
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FhirMetaHelper metaHelper;
    
    @Autowired
    private DeleteService deleteService;
    
    @Autowired
    private CollectionRoutingService collectionRoutingService;
    
    @Autowired
    private CouchbaseGateway couchbaseGateway;
    
    /**
     * Create or update a FHIR resource via PUT operation.
     * Always uses the client-supplied ID and handles proper versioning.
     * Gets cluster through CouchbaseGateway for circuit breaker protection.
     * 
     * @param resource The FHIR resource to create/update (must have client-supplied ID)
     * @param context The transaction context (standalone or nested)
     * @return The created/updated resource with proper versioning metadata
     */
    public Resource updateOrCreateResource(Resource resource, TransactionContext context) {
        String resourceType = resource.getResourceType().name();
        String clientId = resource.getIdElement().getIdPart();
        
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("PUT operation requires a client-supplied ID");
        }
        
        String documentKey = resourceType + "/" + clientId;
        logger.info("🔄 PUT {}: Using client-supplied ID {}", resourceType, clientId);
        
        // ✅ Get cluster through gateway for circuit breaker protection
        Cluster cluster = couchbaseGateway.getClusterForTransaction("default");
        String bucketName = context.getBucketName();
        
        // ✅ FHIR Compliance: Check if ID was previously deleted (tombstoned)
        // Per FHIR spec, cannot reuse deleted resource IDs
        // NOTE: Tombstone check is done inside handleVersioningAndUpdate to ensure it's within transaction context
        
        if (context.isInTransaction()) {
            // Operate within existing Bundle transaction
            return updateResourceInTransaction(resource, documentKey, context, cluster, bucketName);
        } else {
            // Create standalone transaction for this PUT operation
            return updateResourceWithStandaloneTransaction(resource, documentKey, context, cluster, bucketName);
        }
    }
    
    /**
     * Handle PUT operation within existing transaction (Bundle context)
     */
    private Resource updateResourceInTransaction(Resource resource, String documentKey, 
                                                TransactionContext context, Cluster cluster, String bucketName) {
        String resourceType = resource.getResourceType().name();
        
        try {
            // Handle versioning and update within the existing transaction
            handleVersioningAndUpdate(resource, documentKey, context.getTransactionContext(), 
                                    cluster, bucketName);
            
            logger.info("✅ PUT {} (in transaction): Updated resource {}", resourceType, documentKey);
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ PUT {} (in transaction) failed: {}", resourceType, e.getMessage());
            throw new RuntimeException("PUT operation failed in transaction: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle PUT operation with standalone transaction
     */
    private Resource updateResourceWithStandaloneTransaction(Resource resource, String documentKey, 
                                                           TransactionContext context, Cluster cluster, String bucketName) {
        String resourceType = resource.getResourceType().name();
        
        try {
            // Create standalone transaction for this PUT operation
            logger.info("🔄 PUT {}: Starting standalone transaction for {}", resourceType, documentKey);
            cluster.transactions().run(txContext -> {
                logger.debug("🔄 PUT {}: Inside transaction context", resourceType);
                handleVersioningAndUpdate(resource, documentKey, txContext, cluster, bucketName);
                logger.debug("✅ PUT {}: Transaction operations completed", resourceType);
            });
            
            logger.info("✅ PUT {}: Updated resource {} (standalone transaction committed)", resourceType, documentKey);
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ PUT {} (standalone transaction) failed: {}", resourceType, e.getMessage());
            throw new RuntimeException("PUT operation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle FHIR versioning and resource update within transaction context
     */
    private void handleVersioningAndUpdate(Resource resource, String documentKey,
                                         com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                         Cluster cluster, String bucketName) {
        String resourceType = resource.getResourceType().name();
        String clientId = resource.getIdElement().getIdPart();
        
        // Check if ID was previously deleted (tombstoned) within transaction context
        if (deleteService.isTombstonedInTransaction(resourceType, clientId, txContext, cluster, bucketName)) {
            logger.warn("🚫 PUT {}: ID was previously deleted and cannot be reused", documentKey);
            throw new ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException(
                "Resource ID " + clientId + " was previously deleted and cannot be reused. Please choose a new ID."
            );
        }
        
        // Step 1: Copy existing resource to Versions collection (if it exists) and get next version
        int nextVersion = copyExistingResourceToVersions(txContext, cluster, bucketName, resourceType, documentKey);
        
        if (nextVersion > 1) {
            logger.debug("📋 PUT {}: Resource exists, copied to Versions, updating to version {}", 
                       documentKey, nextVersion);
            updateResourceMetadata(resource, String.valueOf(nextVersion), "UPDATE");
        } else {
            logger.debug("🆕 PUT {}: Creating new resource (version 1)", documentKey);
            updateResourceMetadata(resource, "1", "CREATE");
        }
        
        // Step 2: UPSERT the resource with new/updated version
        upsertResourceInTransaction(txContext, cluster, bucketName, resourceType, documentKey, resource);
    }
    
    /**
     * Copy existing resource to Versions collection using efficient N1QL and return next version number
     * Uses UPSERT to handle race conditions when multiple concurrent requests update the same resource
     * 
     * @return Next version number (1 if resource doesn't exist, current+1 if it does)
     */
    private int copyExistingResourceToVersions(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                              Cluster cluster, String bucketName, String resourceType, String documentKey) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Use UPSERT instead of INSERT to handle concurrent updates gracefully
            // This prevents "Duplicate Key" errors when multiple requests update the same resource
            String sql = String.format(
                "UPSERT INTO `%s`.`%s`.`%s` (KEY k, VALUE v) " +
                "SELECT " +
                "    CONCAT(META(r).id, '/', IFNULL(r.meta.versionId, '1')) AS k, " +
                "    r AS v " +
                "FROM `%s`.`%s`.`%s` r " +
                "USE KEYS '%s' " +
                "RETURNING RAW %s.meta.versionId",
                bucketName, DEFAULT_SCOPE, VERSIONS_COLLECTION,  // UPSERT INTO Versions
                bucketName, DEFAULT_SCOPE, targetCollection,     // FROM TargetCollection
                documentKey,                                     // USE KEYS 'Patient/simple-patient-1'
                VERSIONS_COLLECTION                              // RETURNING RAW Versions.meta.versionId
            );
            
            logger.debug("🔄 Copying to Versions with USE KEYS: {}", sql);
            
            // Execute query within transaction context
            com.couchbase.client.java.transactions.TransactionQueryResult result = txContext.query(sql);
            
            // Get RETURNING results from SDK - this gives us the CURRENT version that was copied
            List<String> rows = result.rowsAs(String.class);
            if (!rows.isEmpty()) {
                String currentVersionStr = rows.get(0);
                int currentVersion = Integer.parseInt(currentVersionStr);
                int nextVersion = currentVersion + 1;
                logger.debug("📊 Document exists, current version: {}, next version: {}", currentVersion, nextVersion);
                return nextVersion;
            } else {
                logger.debug("🆕 Document doesn't exist, using version 1");
                return 1;
            }
            
        } catch (Exception e) {
            // Log at debug level to reduce noise - this is expected for new resources
            logger.debug("Resource {} doesn't exist or copy failed: {}", documentKey, e.getMessage());
            // If copy fails, assume resource doesn't exist (version 1)
            return 1;
        }
    }
    
    /**
     * Update resource metadata (version, lastUpdated, audit info)
     */
    private void updateResourceMetadata(Resource resource, String versionId, String operation) {
        // Apply proper meta using new architecture
        MetaRequest metaRequest;
        if ("CREATE".equals(operation) || "1".equals(versionId)) {
            metaRequest = MetaRequest.forCreate(null, versionId, null);
        } else {
            metaRequest = MetaRequest.forUpdate(null, versionId, null);
        }
        metaHelper.applyMeta(resource, metaRequest);
        
        logger.debug("🏷️ Updated metadata: version={}, operation={}", versionId, operation);
    }
    
    /**
     * UPSERT resource in transaction context
     */
    private void upsertResourceInTransaction(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                           Cluster cluster, String bucketName, String resourceType,
                                           String documentKey, Resource resource) {
        try {
            // Get the correct target collection for this resource type
            String targetCollection = collectionRoutingService.getTargetCollection(resourceType);
            
            // Get collection reference
            com.couchbase.client.java.Collection collection = 
                cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(targetCollection);
            
            // Convert to JSON and upsert (try replace first, then insert if not found)
            String resourceJson = jsonParser.encodeResourceToString(resource);
            
            try {
                // First try to get the document for replace
                var existingDoc = txContext.get(collection, documentKey);
                txContext.replace(existingDoc, JsonObject.fromJson(resourceJson));
            } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                // Document doesn't exist, use insert
                txContext.insert(collection, documentKey, JsonObject.fromJson(resourceJson));
            }
            
            logger.debug("🔧 Upserted resource in transaction: {}", documentKey);
            
        } catch (Exception e) {
            logger.error("❌ Failed to upsert resource {} in transaction: {}", documentKey, e.getMessage());
            throw new RuntimeException("Failed to update resource: " + e.getMessage(), e);
        }
    }
}
