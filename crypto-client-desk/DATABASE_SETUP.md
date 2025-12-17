# Client Database Setup

The client application stores all chat messages locally in a database. This allows messages to be restored when reconnecting to a chat.

## Database Options

The client supports two database options:

### 1. SQLite (Default - File-based)
- **Type**: File-based SQLite database
- **Location**: `client_messages.db` in the application directory
- **No setup required** - works out of the box
- **Best for**: Single-user desktop applications

### 2. PostgreSQL (Docker)
- **Type**: PostgreSQL database in Docker container
- **Port**: 5434 (to avoid conflict with server database on 5433)
- **Requires**: Docker and Docker Compose
- **Best for**: Multi-instance or production deployments

## Configuration

### Using SQLite (Default)

No configuration needed. The application will automatically use SQLite.

**For multiple clients on the same machine**, use different database names:
```bash
# Client 1 (User: alice)
java -Ddb.name=alice -jar client.jar

# Client 2 (User: bob)
java -Ddb.name=bob -jar client.jar
```

This creates separate SQLite files: `client_messages_alice.db` and `client_messages_bob.db`

### Using PostgreSQL with Docker

1. **Start the database container:**
   ```bash
   cd crypto-client-desk
   docker-compose up -d
   ```

2. **Create user-specific databases:**
   ```bash
   docker exec -it crypto-client-db psql -U clientuser -d postgres
   ```
   Then in PostgreSQL:
   ```sql
   CREATE DATABASE clientdb_alice;
   CREATE DATABASE clientdb_bob;
   \q
   ```

3. **Configure the application:**
   
   **Option A: Set system properties when running (Recommended for multiple clients):**
   ```bash
   # Client 1 (User: alice)
   java -Ddb.type=postgres -Ddb.name=alice -Ddb.user=clientuser -Ddb.password=clientpass -jar client.jar
   
   # Client 2 (User: bob)
   java -Ddb.type=postgres -Ddb.name=bob -Ddb.user=clientuser -Ddb.password=clientpass -jar client.jar
   ```
   
   **Option B: Edit `src/main/resources/application.yml`:**
   ```yaml
   db:
     type: postgres
     url: jdbc:postgresql://localhost:5434
     user: clientuser
     password: clientpass
     name: clientdb  # Change this per client instance
   ```

4. **Verify the database is running:**
   ```bash
   docker ps
   ```
   You should see `crypto-client-db` container running.

### Running Multiple Clients on Same Machine

To run multiple client instances on the same machine without conflicts:

**For SQLite:**
- Each client uses a different database name via `-Ddb.name=username`
- Creates separate files: `client_messages_username.db`

**For PostgreSQL:**
- Create separate databases: `clientdb_username1`, `clientdb_username2`, etc.
- Run each client with: `-Ddb.type=postgres -Ddb.name=username1`
- The database name is automatically appended to the connection URL

## Database Schema

The `messages` table stores:
- `id`: Primary key
- `chat_id`: ID of the chat
- `sender`: Username of the message sender
- `receiver`: Username of the message receiver
- `message`: Message content
- `type`: Message type (TEXT, ENCRYPTED, FILE, ENCRYPTED_FILE)
- `timestamp`: When the message was received
- `file_id`: File ID for file messages (nullable)

## Features

- **Automatic message storage**: All messages are automatically saved when received
- **Message restoration**: When reconnecting to a chat, all previous messages are loaded
- **Chat deletion**: When a chat is deleted, all associated messages are removed from the database
- **Message types**: Supports text, encrypted, file, and encrypted file messages

## Troubleshooting

### SQLite Issues
- If you see "database locked" errors, ensure no other instance of the application is running
- The database file is created automatically on first run

### PostgreSQL Issues
- Ensure Docker is running: `docker ps`
- Check container logs: `docker logs crypto-client-db`
- Verify port 5434 is not in use by another service
- Check connection string matches the Docker configuration

### Configuration Priority
1. System properties (`-Ddb.type=...`)
2. `application.yml` file
3. Default values (SQLite)

