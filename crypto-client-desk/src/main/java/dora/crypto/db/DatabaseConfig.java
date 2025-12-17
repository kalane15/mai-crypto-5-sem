package dora.crypto.db;

import java.io.InputStream;

/**
 * Reads database configuration from application.yml or system properties.
 */
public class DatabaseConfig {
    private static final String DEFAULT_DB_TYPE = "sqlite";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5434/clientdb";
    private static final String DEFAULT_DB_USER = "clientuser";
    private static final String DEFAULT_DB_PASSWORD = "clientpass";
    private static final String DEFAULT_DB_NAME = "clientdb";

    private String dbType;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private String dbName;

    public DatabaseConfig() {
        loadConfig();
    }

    private void loadConfig() {
        // First, check system properties (highest priority)
        dbType = System.getProperty("db.type", null);
        dbUrl = System.getProperty("db.url", null);
        dbUser = System.getProperty("db.user", null);
        dbPassword = System.getProperty("db.password", null);
        dbName = System.getProperty("db.name", null);

        // Load from application.yml for values not set via system properties
        String yamlType = null;
        String yamlUrl = null;
        String yamlUser = null;
        String yamlPassword = null;
        String yamlName = null;
        
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("application.yml");
            if (is != null) {
                String content = new String(is.readAllBytes());
                yamlType = extractYamlValue(content, "db.type", DEFAULT_DB_TYPE);
                yamlUrl = extractYamlValue(content, "db.url", DEFAULT_DB_URL);
                yamlUser = extractYamlValue(content, "db.user", DEFAULT_DB_USER);
                yamlPassword = extractYamlValue(content, "db.password", DEFAULT_DB_PASSWORD);
                yamlName = extractYamlValue(content, "db.name", DEFAULT_DB_NAME);
            }
        } catch (Exception e) {
            System.err.println("Failed to load database config from application.yml: " + e.getMessage());
        }

        // Use system property if set, otherwise use yaml value, otherwise use default
        dbType = dbType != null ? dbType : (yamlType != null ? yamlType : DEFAULT_DB_TYPE);
        dbUrl = dbUrl != null ? dbUrl : (yamlUrl != null ? yamlUrl : DEFAULT_DB_URL);
        dbUser = dbUser != null ? dbUser : (yamlUser != null ? yamlUser : DEFAULT_DB_USER);
        dbPassword = dbPassword != null ? dbPassword : (yamlPassword != null ? yamlPassword : DEFAULT_DB_PASSWORD);
        dbName = dbName != null ? dbName : (yamlName != null ? yamlName : DEFAULT_DB_NAME);
        
        // If dbName is set, update the URL for PostgreSQL to use the specific database
        if (dbName != null && "postgres".equals(dbType)) {
            // Extract base URL (host:port) and construct new URL with database name
            // URL format: jdbc:postgresql://host:port/database
            String baseUrl = dbUrl;
            if (baseUrl.contains("/")) {
                int lastSlash = baseUrl.lastIndexOf('/');
                if (lastSlash >= 0) {
                    String hostPort = baseUrl.substring(0, lastSlash + 1);
                    dbUrl = hostPort + dbName;
                }
            } else {
                // URL doesn't have database part, append it
                dbUrl = baseUrl + "/" + dbName;
            }
        }
        
        // Debug output to verify configuration
        System.out.println("Database Configuration:");
        System.out.println("  Type: " + dbType);
        System.out.println("  URL: " + dbUrl);
        System.out.println("  User: " + dbUser);
        System.out.println("  Database Name: " + dbName);
    }

    private String extractYamlValue(String yaml, String key, String defaultValue) {
        try {
            String[] lines = yaml.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith(key + ":")) {
                    String value = line.substring(key.length() + 1).trim();
                    // Remove quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    } else if (value.startsWith("'") && value.endsWith("'")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    // Handle ${VAR:default} syntax
                    if (value.startsWith("${") && value.endsWith("}")) {
                        String varPart = value.substring(2, value.length() - 1);
                        int colonIndex = varPart.indexOf(':');
                        if (colonIndex > 0) {
                            String envVar = varPart.substring(0, colonIndex);
                            String defaultVal = varPart.substring(colonIndex + 1);
                            String envValue = System.getenv(envVar);
                            return envValue != null ? envValue : defaultVal;
                        }
                    }
                    return value.isEmpty() ? defaultValue : value;
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return defaultValue;
    }

    public String getDbType() {
        return dbType;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getDbName() {
        return dbName;
    }
}

