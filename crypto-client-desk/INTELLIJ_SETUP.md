# Running Multiple Clients in IntelliJ IDEA

This guide explains how to run multiple client instances in IntelliJ IDEA with different database configurations.

## Method 1: Using Run Configurations (Recommended)

### Step 1: Create First Run Configuration

1. **Open Run/Debug Configurations:**
   - Go to `Run` → `Edit Configurations...`
   - Or click the dropdown next to the run button and select `Edit Configurations...`

2. **Create New Configuration:**
   - Click the `+` button
   - Select `Application`

3. **Configure First Client (e.g., Alice):**
   - **Name**: `Client - Alice` (or any name you prefer)
   - **Main class**: `dora.crypto.Main`
   - **VM options**: 
     ```
     -Ddb.name=alice
     ```
   - **Working directory**: `$MODULE_DIR$` or your project root
   - **Use classpath of module**: `crypto-client-desk`

4. **Click `Apply`**

### Step 2: Create Second Run Configuration

1. **Duplicate the first configuration:**
   - Select `Client - Alice` configuration
   - Click the duplicate button (two overlapping squares icon)
   - Or right-click and select `Copy Configuration`

2. **Configure Second Client (e.g., Bob):**
   - **Name**: `Client - Bob`
   - **VM options**: 
     ```
     -Ddb.name=bob
     ```
   - Keep all other settings the same

3. **Click `Apply` and `OK`**

### Step 3: Run Both Clients

1. **Run both configurations:**
   - Select both configurations in the dropdown (hold `Ctrl`/`Cmd` to select multiple)
   - Click the run button
   - Or run them separately by selecting each one and clicking run

2. **Alternative: Run in parallel:**
   - Run the first client normally
   - Run the second client - IntelliJ will ask if you want to stop the first one or run in parallel
   - Select "Run" to run both simultaneously

## Method 2: Using PostgreSQL with Different Databases

If you're using PostgreSQL, you need to:

1. **Create databases first:**
   ```bash
   docker exec -it crypto-client-db psql -U clientuser -d postgres
   ```
   Then in PostgreSQL:
   ```sql
   CREATE DATABASE alice;
   CREATE DATABASE bob;
   \q
   ```

2. **Configure VM options for PostgreSQL:**
   
   **Client - Alice:**
   ```
   -Ddb.type=postgres -Ddb.name=alice -Ddb.user=clientuser -Ddb.password=clientpass
   ```
   
   **Client - Bob:**
   ```
   -Ddb.type=postgres -Ddb.name=bob -Ddb.user=clientuser -Ddb.password=clientpass
   ```

## Method 3: Using Environment Variables

Instead of VM options, you can use environment variables:

1. **In Run Configuration:**
   - Go to `Environment variables`
   - Click the folder icon to add variables
   - Add: `DB_NAME=alice` (for first client)
   - Add: `DB_NAME=bob` (for second client)

2. **For PostgreSQL, also add:**
   - `DB_TYPE=postgres`
   - `DB_USER=clientuser`
   - `DB_PASSWORD=clientpass`

## Quick Setup Example

### For SQLite (Simplest):

**Client 1 Configuration:**
- Name: `Client - Alice`
- VM options: `-Ddb.name=alice`

**Client 2 Configuration:**
- Name: `Client - Bob`
- VM options: `-Ddb.name=bob`

### For PostgreSQL:

**Client 1 Configuration:**
- Name: `Client - Alice (PostgreSQL)`
- VM options: `-Ddb.type=postgres -Ddb.name=alice -Ddb.user=clientuser -Ddb.password=clientpass`

**Client 2 Configuration:**
- Name: `Client - Bob (PostgreSQL)`
- VM options: `-Ddb.type=postgres -Ddb.name=bob -Ddb.user=clientuser -Ddb.password=clientpass`

## Tips

1. **Save configurations:** IntelliJ will save your run configurations in `.idea/runConfigurations/` directory
2. **Quick switch:** Use the run configuration dropdown to quickly switch between clients
3. **Debug mode:** You can also run both in debug mode simultaneously
4. **Console output:** Each client will have its own console tab, making it easy to see logs from each instance

## Troubleshooting

- **Port already in use:** Make sure you're using different database names (SQLite) or different databases (PostgreSQL)
- **Database connection errors:** For PostgreSQL, ensure the databases are created before running
- **Can't run two instances:** Make sure "Allow parallel run" is enabled in the run configuration (gear icon → "Allow parallel run")

