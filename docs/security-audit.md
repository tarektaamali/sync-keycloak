# Security & Audit Notes

Prepared for security review. Companion: [`architecture.md`](architecture.md).

## 1. Scope — what secrets exist

| Secret | Purpose | Where it lives |
|---|---|---|
| Keycloak service-account **client secret** (`user-sync-agent`) | Lets the backend call the Admin API of a Keycloak connection | Vault: `usersync/<conn>#client-secret` |
| LDAP **bind password** | Binds to Samba AD to read users | Vault: `usersync/<conn>#bind-password` |

**What is NOT stored:** no end-user passwords. The application authenticates its own users via **OIDC** against the `app` realm; it never holds user credentials. This is the single most important point for the reviewer: the app is a *consumer* of identity, not a store of it.

## 2. Secret handling — hash vs. encrypt vs. vault

- **User login passwords** would be **hashed** (one-way, Argon2id/bcrypt) — not applicable here, since we store none.
- **Service/connection secrets** must be **presented** to another system, so they cannot be hashed; they must be **vaulted or reversibly protected**.

This project **externalises** those secrets to **HashiCorp Vault**. The profile database (H2) stores only a **reference** (`vault://usersync/<name>#<field>`); the backend fetches the actual value from Vault at call time via `VaultSecretStore`. Secrets never appear in the app DB, in API responses (`ConnectionView` omits them), or in logs.

## 3. Authentication & least privilege

- **App access**: OIDC Authorization Code + PKCE against the `app` realm; all `/api/**` require a valid JWT (Spring Security resource server).
- **Keycloak Admin API**: accessed **only** through a per-realm **service-account client** (`user-sync-agent`) using the **client-credentials** grant — there is **no `admin/admin` master account** in the running system. The service account is granted the least-privilege `realm-management` roles it actually needs: `view-users`, `manage-users`, `view-realm`, `manage-realm` (the last enables reading/creating realm roles used by *include roles*).
- **LDAP**: a dedicated bind DN with read access to the user subtree.

## 4. Standards mapping

| Control in this system | Standard reference |
|---|---|
| Secrets stored in a dedicated secrets manager, referenced not embedded | PCI-DSS §3.5–3.6 (protect & manage keys); NIST SP 800-57 (key management) |
| No plaintext secrets at rest in the app; encryption/custody delegated to Vault | NIST SP 800-53 SC-12 / SC-28; OWASP ASVS V6 (Stored Cryptography) |
| No stored authenticator secrets for end users; OIDC delegation | NIST SP 800-53 IA-5; OWASP ASVS V2 (Authentication) |
| Least-privilege service account, scoped roles | NIST SP 800-53 AC-6 (least privilege) |
| Immutable audit trail of privileged actions | PCI-DSS §10; NIST SP 800-53 AU-2/AU-3 |

## 5. Audit trail

Every executed sync writes a `SyncRun`: `actor` (from the JWT), `timestamp`, `sourceConn → targetConn`, `mode`, `includeRoles`, per-outcome counts (`created/updated/deleted/skipped`), `errorCount`, and `status` (`OK`/`PARTIAL`). Visible in the **History** page and via `GET /api/audit`. Dry-run previews make no changes and are not recorded as runs.

## 6. Dev vs. production

The local stack runs Vault in **dev mode** — in-memory, a fixed root token, unsealed automatically, and re-seeded on boot. **This is not production-safe** and is labelled as such. The production path:

- **Vault**: HA deployment, **KMS/HSM-backed auto-unseal**, **AppRole** or Kubernetes auth (never the root token), secret **rotation** policies, TLS on the API, and the **audit device** enabled.
- **Transport**: TLS for Keycloak, LDAP (LDAPS), and Vault.
- **LDAP auth**: prefer **SASL/GSSAPI (Kerberos)** or **mTLS client-certificate bind** over a bind password; if a bind password is unavoidable, it stays in Vault with rotation.
- **Keycloak client auth**: consider **`private_key_jwt`** (signed assertion, key in HSM/KMS) instead of a shared client secret.

## 7. Known limitations (current iteration)

- Vault is dev-mode (see §6); secrets do not survive a Vault restart and are re-seeded.
- LDAP uses a bind password rather than Kerberos/mTLS.
- H2 is a single-node file store (suitable for this tool's small profile/audit data; not clustered).
- The audit log is append-via-service but not cryptographically tamper-evident; production would ship it to a WORM/central log per PCI-DSS §10.5.
