package com.couchbase.fhir.auth;

import com.couchbase.fhir.auth.SmartScopes.SmartScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SMART on FHIR scope matching logic.
 * 
 * This validates the critical security boundary - ensuring that scopes
 * are properly enforced and users can only access resources they've consented to.
 */
public class SmartScopeMatchingTest {
    
    @Test
    public void testExactResourceMatch_ShouldAllow() {
        SmartScope scope = SmartScopes.parse("patient/Patient.read");
        assertTrue(scope.matches("Patient", "read"), 
                  "patient/Patient.read should allow read access to Patient");
    }
    
    @Test
    public void testExactResourceMismatch_ShouldDeny() {
        SmartScope scope = SmartScopes.parse("patient/Patient.read");
        assertFalse(scope.matches("Observation", "read"), 
                   "patient/Patient.read should NOT allow read access to Observation");
    }
    
    @Test
    public void testAllergyIntolerance_WithoutScope_ShouldDeny() {
        // This is the Inferno test case scenario
        SmartScope patientScope = SmartScopes.parse("patient/Patient.read");
        SmartScope conditionScope = SmartScopes.parse("patient/Condition.read");
        SmartScope observationScope = SmartScopes.parse("patient/Observation.read");
        
        // None of these scopes should grant access to AllergyIntolerance
        assertFalse(patientScope.matches("AllergyIntolerance", "read"),
                   "patient/Patient.read should NOT allow AllergyIntolerance access");
        assertFalse(conditionScope.matches("AllergyIntolerance", "read"),
                   "patient/Condition.read should NOT allow AllergyIntolerance access");
        assertFalse(observationScope.matches("AllergyIntolerance", "read"),
                   "patient/Observation.read should NOT allow AllergyIntolerance access");
    }
    
    @Test
    public void testWildcardResource_ShouldAllowAny() {
        SmartScope scope = SmartScopes.parse("patient/*.read");
        assertTrue(scope.matches("Patient", "read"));
        assertTrue(scope.matches("Observation", "read"));
        assertTrue(scope.matches("AllergyIntolerance", "read"));
        assertTrue(scope.matches("Condition", "read"));
    }
    
    @Test
    public void testWildcardAction_ShouldAllowReadAndWrite() {
        SmartScope scope = SmartScopes.parse("patient/Patient.*");
        assertTrue(scope.matches("Patient", "read"));
        assertTrue(scope.matches("Patient", "write"));
    }
    
    @Test
    public void testGranularScope_RS_ShouldAllowRead() {
        // SMART v2 granular scope: .rs = read + search
        SmartScope scope = SmartScopes.parse("patient/Observation.rs");
        assertTrue(scope.matches("Observation", "read"),
                  "patient/Observation.rs should allow read");
    }
    
    @Test
    public void testGranularScope_CUD_ShouldAllowWrite() {
        // SMART v2 granular scope: .cud = create + update + delete
        SmartScope scope = SmartScopes.parse("patient/Patient.cud");
        assertTrue(scope.matches("Patient", "write"),
                  "patient/Patient.cud should allow write");
    }
    
    @Test
    public void testGranularScope_CRUDS_ShouldAllowBoth() {
        // SMART v2 granular scope: .cruds = all operations
        SmartScope scope = SmartScopes.parse("patient/Patient.cruds");
        assertTrue(scope.matches("Patient", "read"),
                  "patient/Patient.cruds should allow read");
        assertTrue(scope.matches("Patient", "write"),
                  "patient/Patient.cruds should allow write");
    }
    
    @Test
    public void testGranularScope_RS_ShouldNotAllowWrite() {
        // SMART v2 granular scope: .rs = read + search (no write operations)
        SmartScope scope = SmartScopes.parse("patient/Patient.rs");
        assertFalse(scope.matches("Patient", "write"),
                   "patient/Patient.rs should NOT allow write");
    }
    
    @Test
    public void testGranularScope_CUD_ShouldNotAllowRead() {
        // SMART v2 granular scope: .cud = create + update + delete (no read)
        SmartScope scope = SmartScopes.parse("patient/Patient.cud");
        assertFalse(scope.matches("Patient", "read"),
                   "patient/Patient.cud should NOT allow read");
    }
    
    @Test
    public void testUserScope_DifferentCategory() {
        SmartScope scope = SmartScopes.parse("user/Patient.read");
        assertTrue(scope.isUserScope());
        assertFalse(scope.isPatientScope());
        assertTrue(scope.matches("Patient", "read"));
    }
    
    @Test
    public void testSystemScope_DifferentCategory() {
        SmartScope scope = SmartScopes.parse("system/Patient.read");
        assertTrue(scope.isSystemScope());
        assertFalse(scope.isPatientScope());
        assertTrue(scope.matches("Patient", "read"));
    }
    
    @Test
    public void testNullScope_ShouldReturnNull() {
        SmartScope scope = SmartScopes.parse(null);
        assertNull(scope);
    }
    
    @Test
    public void testEmptyScope_ShouldReturnNull() {
        SmartScope scope = SmartScopes.parse("");
        assertNull(scope);
    }
    
    @Test
    public void testInvalidScope_NoSlash_ReturnsSpecialScope() {
        SmartScope scope = SmartScopes.parse("openid");
        assertNotNull(scope);
        assertEquals("openid", scope.getOriginalScope());
        assertNull(scope.getCategory());
        assertNull(scope.getResourceType());
    }
    
    @Test
    public void testInvalidScope_NoDot_ShouldReturnNull() {
        SmartScope scope = SmartScopes.parse("patient/Patient");
        assertNull(scope, "Scope without dot should be invalid");
    }
}

