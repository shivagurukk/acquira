# Project Migration Guide for Acquira

This guide details how to move the **Acquira** project to a new PC and set it up for development.

## 1. Prerequisites (New PC)

Before transferring files, ensure the new PC has the following software installed:

### A. Java Development Kit (JDK)
- **Version**: Java 17 (Required by `pom.xml`)
- **Download**: [Eclipse Adoptium (Temurin)](https://adoptium.net/temurin/releases/?version=17) or Oracle JDK 17.
- **Verify**: Run `java -version` in a terminal.

### B. Node.js & npm
- **Version**: LTS (Long Term Support) recommended (v20+).
- **Download**: [Node.js Official Site](https://nodejs.org/)
- **Verify**: Run `node -v` and `npm -v`.

### C. PostgreSQL Database
- **Version**: PostgreSQL 14, 15, or 16.
- **Download**: [PostgreSQL Downloads](https://www.postgresql.org/download/)
- **Configuration MATCH**:
  - **Port**: `5433` (Important: The project is configured for port 5433, not the default 5432).
  - **Username**: `postgres`
  - **Password**: `postgres`
  - **Database Name**: `postgres` (Default)
- **Tool**: Install **pgAdmin** (usually comes with the installer) to manage the DB.

### D. Maven (Build Tool)
- **Download**: [Apache Maven](https://maven.apache.org/download.cgi)
- **Install**: Unzip and add the `bin` folder to your system `PATH`.
- **Verify**: Run `mvn -version`.

---

## 2. Transferring the Project

1.  **On Old PC**:
    - Zip the entire `Acquira` folder.
    - **Database Dump** (Optional but recommended if you want to keep existing data):
      - Open a terminal/command prompt.
      - Run: `pg_dump -U postgres -p 5433 -F c -b -v -f acquira_backup.dump postgres`
      - Copy `acquira_backup.dump` to the zip or transfer it separately.

2.  **On New PC**:
    - Unzip `Acquira.zip` to your desired location (e.g., `D:\Projects\Acquira`).

---

## 3. Database Configuration & Setup

### A. Configuration (Where to change settings)
If your new PC has a different PostgreSQL port, username, or password, edit the following file:
**File**: `src/main/resources/application.properties`

```properties
# Change port (e.g., 5432) or DB name here
spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/postgres?reWriteBatchedInserts=true

# Change credentials here
spring.datasource.username=postgres
spring.datasource.password=postgres
```

### B. Database Initialization (Automatic)
Your project contains `schema.sql` and `data.sql`, and is configured to run them automatically.
1.  **Create the Database**: Ensure a database named `postgres` exists (default in PostgreSQL).
2.  **Run the App**: When you start the Backend (Maven step), it will automatically:
    - Create all tables (`schema.sql`).
    - Insert initial data (`data.sql`).

**No manual script execution is required.**

### C. Restore Data (Optional - Only for existing data)
*Skip this if you are happy with the clean slate/default data.*
If you need the specific transactions/merchants from your old PC that aren't in `data.sql`, perform the restore:
1.  Open Terminal/pgAdmin.
2.  Run: `pg_restore -U postgres -p 5433 -d postgres -v "acquira_backup.dump"`

---

## 4. Running the Application

You need to run the Backend (API) and Frontend (UI) separately.

### A. Backend (Spring Boot)
1.  Open Command Prompt / PowerShell.
2.  Navigate to the project root:
    ```powershell
    cd path\to\Acquira
    ```
3.  Clean and Install dependencies:
    ```powershell
    mvn clean install
    ```
4.  Run the application:
    ```powershell
    mvn spring-boot:run
    ```
    *Wait until you see "Started AcquiraSystemApplication in ... seconds".*

### B. Frontend (React/Vite)
1.  Open a **new** Command Prompt / PowerShell window.
2.  Navigate to the frontend directory:
    ```powershell
    cd path\to\Acquira\frontend
    ```
3.  Install dependencies (First time only):
    ```powershell
    npm install
    ```
4.  Start the development server:
    ```powershell
    npm run dev
    ```
5.  Access the app at the URL shown (usually `http://localhost:5173`).

---

## Troubleshooting Common Issues

- **Database Connection Error**:
  - Check if PostgreSQL is running.
  - Check if the port matches (Project uses `5433`).
  - Check username/password in `application.properties` (`postgres`/`postgres`).

- **Frontend API Errors**:
  - Ensure the Backend is running **before** you try to log in on the Frontend.
  - Check browser console (F12) for network errors.
