package com.infeara.sales.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed application configuration (all values are externalised, never hard-coded). */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private String baseUrl = "http://localhost:8080";
    private String defaultTimezone = "Asia/Kolkata";
    private String environment = "development";
    private Security security = new Security();
    private Bootstrap bootstrap = new Bootstrap();
    private Worker worker = new Worker();
    private Cors cors = new Cors();
    private Webhooks webhooks = new Webhooks();

    @Getter
    @Setter
    public static class Security {
        private String encryptionKey;
        private String encryptionKeyId = "k1";
        private String previousEncryptionKeys;
        /**
         * "auto" (default) marks the session cookie Secure only when the request itself arrived
         * over TLS; "always" forces it; "never" is for local plain-HTTP development only.
         *
         * <p>Why not simply "production implies Secure": a Tomcat behind a TLS-terminating proxy
         * sees plain HTTP on the connector, and a cookie marked Secure is then silently dropped by
         * every browser and HTTP client. Deriving the flag from the request (with
         * server.forward-headers-strategy advanced in prod) is correct in both topologies.
         */
        private String cookieSecure = "auto";
        private int sessionTtlMinutes = 480;
        private int sessionIdleMinutes = 60;
        private int maxFailedLogins = 5;
        private int lockoutMinutes = 15;
        private Mfa mfa = new Mfa();
        private RateLimit rateLimit = new RateLimit();
    }

    @Getter
    @Setter
    public static class Mfa {
        private boolean enabled = true;
        private String issuer = "Infeara Sales OS";
        private int windowSteps = 1;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int authPerMinute = 20;
        private int apiPerMinute = 600;
        private int webhookPerMinute = 300;
    }

    @Getter
    @Setter
    public static class Bootstrap {
        private String workspaceName = "Infeara Technologies";
        private String workspaceSlug = "infeara";
        private String workspaceTimezone = "Asia/Kolkata";
        private String adminEmail;
        private String adminPassword;
        private String adminDisplayName = "Workspace Administrator";
    }

    @Getter
    @Setter
    public static class Worker {
        private boolean enabled = true;
        private String instanceId;
        private int outboxBatchSize = 25;
        private int outboxLeaseSeconds = 120;
        private int jobsBatchSize = 20;
        private int jobLeaseSeconds = 180;
        private int jobMaxAttempts = 8;
        private int jobBackoffBaseSeconds = 30;
        private int jobBackoffCapSeconds = 3600;
        private long tickIntervalMs = 5000;
    }

    @Getter
    @Setter
    public static class Cors {
        private String allowedOrigins;
    }

    @Getter
    @Setter
    public static class Webhooks {
        private Calendly calendly = new Calendly();

        @Getter
        @Setter
        public static class Calendly {
            private long toleranceSeconds = 180;
        }
    }
}
