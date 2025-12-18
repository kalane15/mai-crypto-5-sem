package dora.crypto.db;

import dora.crypto.model.LocalMessage;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Database service for managing local chat messages.
 * Supports both SQLite (file-based) and PostgreSQL (Docker).
 * Supports user-specific databases via db.name configuration.
 */
public class MessageDatabase {
    private static final String DB_URL_SQLITE_PREFIX = "jdbc:sqlite:client_messages";
    
    private final DatabaseConfig config;
    private Connection connection;

    public MessageDatabase() {
        this.config = new DatabaseConfig();
        initializeDatabase();
    }

    private void initializeDatabase() {
        try {
            String dbType = config.getDbType();
            String connectionUrl;
            
            if ("postgres".equals(dbType)) {
                // PostgreSQL: URL already contains database name from DatabaseConfig
                connectionUrl = config.getDbUrl();
                connection = DriverManager.getConnection(connectionUrl, config.getDbUser(), config.getDbPassword());
            } else {
                // SQLite: Use database name to create user-specific file
                String dbName = config.getDbName();
                if (dbName != null && !dbName.equals("clientdb")) {
                    // User-specific database file
                    connectionUrl = DB_URL_SQLITE_PREFIX + "_" + dbName + ".db";
                } else {
                    // Default database file
                    connectionUrl = DB_URL_SQLITE_PREFIX + ".db";
                }
                connection = DriverManager.getConnection(connectionUrl);
            }
            createTable();
            System.out.println("Connected to " + dbType + " database: " + connectionUrl);
        } catch (SQLException e) {
            System.err.println("Failed to connect to database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTable() throws SQLException {
        String createTableSQL;
        String dbType = config.getDbType();
        if ("postgres".equals(dbType)) {
            createTableSQL = """
                CREATE TABLE IF NOT EXISTS messages (
                    id BIGSERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    sender VARCHAR(255) NOT NULL,
                    receiver VARCHAR(255) NOT NULL,
                    message TEXT NOT NULL,
                    type VARCHAR(50),
                    timestamp TIMESTAMP NOT NULL,
                    file_id VARCHAR(255),
                    local_file_path TEXT
                )
                """;
        } else {
            createTableSQL = """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER NOT NULL,
                    sender TEXT NOT NULL,
                    receiver TEXT NOT NULL,
                    message TEXT NOT NULL,
                    type TEXT,
                    timestamp TEXT NOT NULL,
                    file_id TEXT,
                    local_file_path TEXT
                )
                """;
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
        }
        
        // Add local_file_path column if it doesn't exist (for existing databases)
        try {
            String alterSQL = "ALTER TABLE messages ADD COLUMN local_file_path TEXT";
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(alterSQL);
            }
        } catch (SQLException e) {
            // Column already exists, ignore
        }

        // Create index on chat_id for faster queries
        String createIndexSQL = "CREATE INDEX IF NOT EXISTS idx_chat_id ON messages(chat_id)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createIndexSQL);
        }
    }

    /**
     * Save a message to the database.
     */
    public void saveMessage(LocalMessage localMessage) {
        String sql = "INSERT INTO messages (chat_id, sender, receiver, message, type, timestamp, file_id, local_file_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localMessage.getChatId());
            pstmt.setString(2, localMessage.getSender());
            pstmt.setString(3, localMessage.getReceiver());
            pstmt.setString(4, localMessage.getMessage());
            pstmt.setString(5, localMessage.getType());
            
            String dbType = config.getDbType();
            if ("postgres".equals(dbType)) {
                pstmt.setTimestamp(6, Timestamp.valueOf(localMessage.getTimestamp()));
            } else {
                pstmt.setString(6, localMessage.getTimestamp().toString());
            }
            
            pstmt.setString(7, localMessage.getFileId());
            pstmt.setString(8, localMessage.getLocalFilePath());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to save message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load all messages for a specific chat.
     */
    public List<LocalMessage> loadMessages(Long chatId) {
        List<LocalMessage> messages = new ArrayList<>();
        String sql = "SELECT id, chat_id, sender, receiver, message, type, timestamp, file_id, local_file_path FROM messages WHERE chat_id = ? ORDER BY timestamp ASC";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                String dbType = config.getDbType();
                LocalDateTime timestamp;
                if ("postgres".equals(dbType)) {
                    Timestamp ts = rs.getTimestamp("timestamp");
                    timestamp = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                } else {
                    String tsStr = rs.getString("timestamp");
                    timestamp = tsStr != null ? LocalDateTime.parse(tsStr) : LocalDateTime.now();
                }
                
                LocalMessage msg = LocalMessage.builder()
                        .id(rs.getLong("id"))
                        .chatId(rs.getLong("chat_id"))
                        .sender(rs.getString("sender"))
                        .receiver(rs.getString("receiver"))
                        .message(rs.getString("message"))
                        .type(rs.getString("type"))
                        .timestamp(timestamp)
                        .fileId(rs.getString("file_id"))
                        .localFilePath(rs.getString("local_file_path"))
                        .build();
                
                messages.add(msg);
            }
        } catch (SQLException e) {
            System.err.println("Failed to load messages: " + e.getMessage());
            e.printStackTrace();
        }
        
        return messages;
    }

    /**
     * Delete all messages for a specific chat.
     */
    public void deleteMessagesByChatId(Long chatId) {
        String sql = "DELETE FROM messages WHERE chat_id = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            int deleted = pstmt.executeUpdate();
            System.out.println("Deleted " + deleted + " messages for chat " + chatId);
        } catch (SQLException e) {
            System.err.println("Failed to delete messages: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Close the database connection.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Database connection closed");
            }
        } catch (SQLException e) {
            System.err.println("Error closing database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

