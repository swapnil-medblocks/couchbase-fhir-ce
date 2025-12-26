# Login Page Confusion: Admin vs Patient (OAuth)

## The Problem You're Experiencing

When you visit `https://cbfhir.com`, you get redirected to `https://cbfhir.com/login`. After running SMART/Inferno tests, that same URL sometimes shows the **patient login screen** instead of your expected **admin login screen**, and this persists even after server restart!

## Why This Happens

### You Have TWO Separate Login Systems

Your application has **two completely different authentication systems** that unfortunately share the same URL path:

#### 1. Admin UI Login (React SPA)
- **URL**: `https://cbfhir.com/login` (handled by React Router)
- **Purpose**: For you to manage the system
- **Backend**: `/api/auth/login` (REST API)
- **Token Type**: Admin JWT (`token_type: "admin"`)
- **UI**: React component with email/password
- **Session**: STATELESS (no cookies)

#### 2. Patient OAuth Login (Thymeleaf Template)
- **URL**: `https://cbfhir.com/login` (handled by Spring Controller)
- **Purpose**: For SMART apps to authenticate patients
- **Backend**: `/login` POST (Spring form login)
- **Token Type**: OAuth access token (`token_type: "oauth"`)
- **UI**: Thymeleaf HTML template with username/password
- **Session**: STATEFUL (creates `JSESSIONID` cookie)

### How They Share the Same URL

The routing decision happens based on **who's asking**:

```
Browser navigates to: https://cbfhir.com/login
    ↓
Is this request from React Router (in-page navigation)?
    ↓
YES → React Router shows Admin Login Component
    ↓
NO → Backend's LoginController serves login.html (Patient OAuth)
```

**The catch**: When you do a **full page reload** or type the URL directly, the backend always serves the Thymeleaf template!

### The Sticky Session Bug

Here's what happens during an Inferno test:

1. **Inferno initiates OAuth flow**: `GET /oauth2/authorize?...`
2. **Spring Security intercepts**: User not authenticated, redirect to `/login`
3. **Patient enters credentials**: Form POST to `/login`
4. **Spring creates session**: `JSESSIONID` cookie set with `SameSite=None; Secure`
5. **Session persists**: This cookie remains in your browser!
6. **Next visit to cbfhir.com**: Browser sends `JSESSIONID`
7. **Spring thinks**: "Oh, this user is in an OAuth flow" → serves patient login

Even after server restart, **the browser still has the cookie** and keeps sending it!

## The Solution

We need to **isolate the OAuth login flow** from your normal admin access. Here are the options:

### Option 1: Different URLs (Recommended ✅)

Change the OAuth login to use a different path:

**Changes needed**:
1. OAuth login → `/oauth/login` (or `/patient-login`)
2. Admin UI stays → `https://cbfhir.com/` → React login component

This completely separates the two flows.

### Option 2: Clear Session After OAuth (Partial Fix)

Add session clearing after OAuth consent is granted. This helps but doesn't completely solve the issue if users abandon the flow.

### Option 3: Use Separate Subdomain (Best for Production)

- Admin UI: `https://admin.cbfhir.com`
- FHIR + OAuth: `https://api.cbfhir.com` or `https://cbfhir.com`

This is the cleanest separation.

## Implementation: Option 1 (Different URLs)

Let me implement the URL separation for you:

### Step 1: Update OAuth Login Path

```java
// LoginController.java
@GetMapping("/oauth/login")  // Changed from /login
public String login(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Expires", "0");
    return "login";
}
```

### Step 2: Update Authorization Server Config

```java
// AuthorizationServerConfig.java
http.formLogin(form -> form
    .loginPage("/oauth/login")  // Changed from /login
    .permitAll()
);

http.exceptionHandling((exceptions) -> exceptions
    .defaultAuthenticationEntryPointFor(
        new LoginUrlAuthenticationEntryPoint("/oauth/login"),  // Changed
        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
    )
);
```

### Step 3: Update Security Config

```java
// SecurityConfig.java
.requestMatchers("/oauth/login", "/error", "/css/**", "/js/**").permitAll()
```

This way:
- `https://cbfhir.com/login` → Always React admin login
- `https://cbfhir.com/oauth/login` → OAuth patient login (only triggered by SMART apps)

---

## Quick Fix: Clear Your Browser Session

For immediate relief, do one of these:

### Method 1: Clear Cookies
```
1. Open Developer Tools (F12)
2. Application/Storage tab
3. Cookies → https://cbfhir.com
4. Delete JSESSIONID
5. Refresh
```

### Method 2: Incognito/Private Window
```
Open cbfhir.com in incognito mode
→ No session cookies
→ Always see React admin login
```

### Method 3: Clear Site Data
```
Chrome: Settings → Privacy → Clear browsing data → Cookies (last hour)
```

---

## Current Architecture Diagram

```
┌─────────────────────────────────────────────┐
│ Browser: https://cbfhir.com                 │
└─────────────────┬───────────────────────────┘
                  │
        ┌─────────▼──────────┐
        │ Full page reload?  │
        └────┬────────────┬──┘
             │ YES        │ NO (React Router)
             │            │
   ┌─────────▼──────┐   ┌▼────────────────┐
   │ Backend serves │   │ React serves    │
   │ login.html     │   │ Login component │
   │ (OAuth flow)   │   │ (Admin UI)      │
   └────────────────┘   └─────────────────┘
```

## Recommended Fix Architecture

```
┌──────────────────────────────────────────────┐
│ Browser: https://cbfhir.com                  │
│   → React App → Admin Login Component       │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ Inferno: https://cbfhir.com/oauth/login      │
│   → Backend serves login.html (OAuth)       │
└──────────────────────────────────────────────┘
```

---

## Why Session Configuration Shows `SameSite=None`

In your `application.yml`:

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: none # Required for cross-site OAuth (Inferno → cbfhir.com)
        secure: true
```

This is **required for SMART on FHIR** because:
- Inferno runs on a different domain (e.g., `inferno.healthit.gov`)
- It redirects to your server (`cbfhir.com`)
- Browser needs to maintain session across domains
- `SameSite=None` + `Secure` allows this

**Side effect**: The session cookie persists longer and works everywhere, including your admin access!

---

## Do You Want Me to Implement the Fix?

I can implement **Option 1** (separate URLs) right now. This will:

✅ Keep `/login` for your admin UI (React)  
✅ Move OAuth to `/oauth/login` (patient authentication)  
✅ Eliminate the confusion completely  
✅ Still pass all Inferno tests

Would you like me to proceed with this fix?

---

## Reference Files

- `backend/src/main/java/com/couchbase/fhir/auth/controller/LoginController.java` - OAuth login page
- `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java` - OAuth server config
- `backend/src/main/resources/templates/login.html` - Patient login UI (Thymeleaf)
- `frontend/src/pages/Auth/Login.tsx` - Admin login UI (React)
- `backend/src/main/resources/application.yml` - Session configuration

---

**Current Status**: Confusing but functional  
**Recommended Action**: Implement URL separation (Option 1)  
**Estimated Time**: 5 minutes  
**Risk**: Low (purely URL routing change, no business logic impact)

