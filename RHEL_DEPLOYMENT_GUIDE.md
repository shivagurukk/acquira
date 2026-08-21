# Acquira Platform — RHEL Production Deployment Guide

**Tested on:** RHEL 10 (Feb 14, 2026)
**Deployment method:** Manual file upload via SCP/SFTP (no git)

---

## Architecture

```
acquira-core.jar  ← THE ONLY JAR YOU RUN (single process, port 8081)
  ├── acquira-common  (library — shared models, security, repositories)
  ├── acquira-batch   (library — file upload, batch processing)
  └── acquira-pdf     (library — Playwright/Chromium PDF engine)
```

**Stack:** Java 21 + Spring Boot 3.2 • React 19 • PostgreSQL 15 • Playwright Chromium • Nginx

> **Key points:**
> - `acquira-common`, `acquira-batch`, `acquira-pdf` are **library JARs** — embedded inside `acquira-core.jar`
> - Only `acquira-core` is runnable — the others have no `main()` class
> - Playwright requires `driver-bundle` dependency + pre-extracted driver + Chromium browser
> - Zero internet access needed at runtime (all assets bundled in classpath)

---

## Path Configuration

All paths are configurable via Spring profiles:

| Property | Windows (dev) | RHEL (prod) | Env Variable |
|----------|--------------|-------------|-------------|
| `app.logs.dir` | `logs/` (relative) | `/opt/acquira/logs` | `APP_LOGS_DIR` |
| `app.reports.dir` | `reports/` (relative) | `/opt/acquira/reports` | `APP_REPORTS_DIR` |
| `app.data.dir` | `data/` (relative) | `/opt/acquira/data` | `APP_DATA_DIR` |

- `application.properties` — base config with relative paths (Windows dev)
- `application-prod.properties` — activated with `--spring.profiles.active=prod`, uses absolute Linux paths
- Environment variables override both (highest priority)

---

## PHASE 1: Server Preparation

### 1.1 System Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| OS | RHEL 8/9/10, AlmaLinux, Rocky Linux | RHEL 9/10 |
| CPU | 4 cores | 8 cores |
| RAM | 8 GB | 16 GB |
| Disk | 50 GB | 100 GB SSD |
| Ports | 80, 443, 8081, 5432 | Same |

### 1.2 Create Application User (as root)

```bash
sudo useradd -m -s /bin/bash acquira
sudo passwd acquira
```

### 1.3 Create Directory Structure (as root)

```bash
sudo mkdir -p /opt/acquira/{source,app,config,logs,data,reports,frontend,backups,tmp,playwright-driver}
sudo chown -R acquira:acquira /opt/acquira
sudo chmod -R u+rwX /opt/acquira
```

| Directory | Purpose |
|-----------|---------|
| `/opt/acquira/source` | Uploaded source code (for building) |
| `/opt/acquira/app` | Deployed JAR file |
| `/opt/acquira/config` | External `application-prod.properties` |
| `/opt/acquira/logs` | Application logs (`core.log`) |
| `/opt/acquira/data` | File uploads + Java tmpdir |
| `/opt/acquira/reports` | Generated PDF reports (`reports/2026-01/*.pdf`) |
| `/opt/acquira/frontend` | React static build files |
| `/opt/acquira/backups` | Database backup dumps |
| `/opt/acquira/playwright-driver` | Pre-extracted Playwright Node.js driver |

### 1.4 Install Java 21 (as root)

```bash
sudo dnf install java-21-openjdk java-21-openjdk-devel -y

java -version
# openjdk version "21.x.x"

echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk' | sudo tee /etc/profile.d/java.sh
source /etc/profile.d/java.sh
```

### 1.5 Install Maven 3.9+ (as root)

```bash
cd /tmp
curl -O https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz
sudo tar -xzf apache-maven-3.9.6-bin.tar.gz -C /opt
sudo ln -s /opt/apache-maven-3.9.6 /opt/maven

echo 'export M2_HOME=/opt/maven
export PATH=$M2_HOME/bin:$PATH' | sudo tee /etc/profile.d/maven.sh
source /etc/profile.d/maven.sh

mvn -version
```

### 1.6 Install Node.js 20+ (as root)

```bash
# Option A: dnf (if available)
sudo dnf install nodejs npm -y

# Option B: NVM (recommended — installs under root)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 20
nvm use 20
```

**IMPORTANT: Make Node.js accessible to the `acquira` user.**

If installed via NVM under root, run as **root**:

```bash
# Remove any dangling symlinks first
rm -f /usr/bin/node /usr/bin/npm /usr/bin/npx

# Copy node binary
cp /root/.nvm/versions/node/v20.20.0/bin/node /usr/bin/node

# Symlink npm/npx (they need to find their modules)
ln -sf /root/.nvm/versions/node/v20.20.0/bin/npm /usr/bin/npm
ln -sf /root/.nvm/versions/node/v20.20.0/bin/npx /usr/bin/npx
ln -sf /root/.nvm/versions/node/v20.20.0/lib/node_modules /usr/lib/node_modules

# Make npm/npx accessible
chmod 755 /root/.nvm/versions/node/v20.20.0/bin/npm
chmod 755 /root/.nvm/versions/node/v20.20.0/bin/npx
chmod -R o+rX /root/.nvm/versions/node/v20.20.0/lib/node_modules
chmod o+x /root /root/.nvm /root/.nvm/versions /root/.nvm/versions/node /root/.nvm/versions/node/v20.20.0 /root/.nvm/versions/node/v20.20.0/bin /root/.nvm/versions/node/v20.20.0/lib

# Verify as acquira
su - acquira -c "node -v && npm -v"
```

### 1.7 Install & Configure PostgreSQL (as root)

```bash
sudo dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm
sudo dnf install postgresql15-server postgresql15 -y
sudo /usr/pgsql-15/bin/postgresql-15-setup initdb
sudo systemctl start postgresql-15
sudo systemctl enable postgresql-15
```

Create database:

```bash
sudo -u postgres psql
```

```sql
CREATE USER acquira_user WITH PASSWORD 'YOUR_STRONG_PASSWORD_HERE';
CREATE DATABASE acquira_db OWNER acquira_user;
GRANT ALL PRIVILEGES ON DATABASE acquira_db TO acquira_user;
\c acquira_db
CREATE EXTENSION IF NOT EXISTS pg_trgm;
GRANT ALL ON SCHEMA public TO acquira_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO acquira_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO acquira_user;
\q
```

Edit `/var/lib/pgsql/15/data/pg_hba.conf` — change `peer`/`ident` to `md5`:

```
local   all   all          md5
host    all   all   127.0.0.1/32   md5
host    all   all   ::1/128        md5
```

Edit `/var/lib/pgsql/15/data/postgresql.conf`:

```ini
listen_addresses = 'localhost'
port = 5432
max_connections = 200
shared_buffers = 2GB
effective_cache_size = 6GB
work_mem = 64MB
```

```bash
sudo systemctl restart postgresql-15
```

### 1.8 Install Nginx (as root)

```bash
sudo dnf install nginx -y
sudo systemctl enable nginx
```

### 1.9 Install Playwright System Dependencies (as root)

```bash
sudo dnf install -y \
    alsa-lib atk at-spi2-atk cups-libs libdrm libXcomposite \
    libXdamage libXrandr mesa-libgbm pango nss nss-util nspr \
    libxshmfence libXScrnSaver gtk3 ipa-gothic-fonts \
    xorg-x11-fonts-100dpi xorg-x11-fonts-75dpi xorg-x11-utils \
    xorg-x11-fonts-cyrillic xorg-x11-fonts-Type1 xorg-x11-fonts-misc \
    libXtst dbus-glib
```

### 1.10 Firewall (as root)

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=8081/tcp
sudo firewall-cmd --reload
```

---

## PHASE 2: Upload Source Code

### 2.1 Upload from Windows via SCP

From Windows PowerShell:

```powershell
scp -r "C:\Users\sivag\Desktop\cms\Acquira\*" acquira@YOUR_SERVER_IP:/opt/acquira/source/
```

Or use **WinSCP / FileZilla** — SFTP to `YOUR_SERVER_IP`, upload contents into `/opt/acquira/source/`.

### 2.2 Verify structure (as acquira)

```bash
ls /opt/acquira/source/
# Must show: acquira-common/ acquira-batch/ acquira-pdf/ acquira-core/ frontend/ pom.xml
```

> If you uploaded the `Acquira` folder itself:
> ```bash
> mv /opt/acquira/source/Acquira/* /opt/acquira/source/
> rmdir /opt/acquira/source/Acquira
> ```

### 2.3 Fix permissions (as root)

```bash
sudo chown -R acquira:acquira /opt/acquira/source
sudo chmod -R u+rwX /opt/acquira/source
```

---

## PHASE 3: Install Playwright Chromium Browser

Run as **acquira** user:

```bash
su - acquira

# Build common first (Playwright install depends on it)
cd /opt/acquira/source/acquira-common
mvn clean install -DskipTests

# Install Chromium browser binary
cd /opt/acquira/source/acquira-pdf
mvn exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

Verify:

```bash
ls /home/acquira/.cache/ms-playwright/chromium-*/chrome-linux/chrome
# Should show the chrome binary (~380MB)

/home/acquira/.cache/ms-playwright/chromium-*/chrome-linux/chrome --version
# Should show: Chromium 120.0.6099.28
```

> **Note:** RHEL shows "BEWARE: your OS is not officially supported" — this is a warning only,
> the Ubuntu fallback build works perfectly on RHEL.

---

## PHASE 4: Build the Application

### 4.1 Build all modules (as acquira)

```bash
cd /opt/acquira/source
mvn clean install -DskipTests
```

Expected output:

```
[INFO] acquira-common ..................................... SUCCESS
[INFO] acquira-batch ...................................... SUCCESS
[INFO] acquira-pdf ........................................ SUCCESS
[INFO] acquira-core ....................................... SUCCESS
[INFO] BUILD SUCCESS
```

If parent build fails, build individually:

```bash
cd /opt/acquira/source/acquira-common && mvn clean install -DskipTests
cd /opt/acquira/source/acquira-batch  && mvn clean install -DskipTests
cd /opt/acquira/source/acquira-pdf    && mvn clean install -DskipTests
cd /opt/acquira/source/acquira-core   && mvn clean package -DskipTests
```

> **"Cannot delete target/" error:** Another Java process is holding the file.
> ```bash
> sudo pkill -f acquira || true
> sudo rm -rf /opt/acquira/source/acquira-*/target
> ```

### 4.2 Deploy the JAR

```bash
mkdir -p /opt/acquira/app
cp /opt/acquira/source/acquira-core/target/acquira-core-1.0.0-SNAPSHOT.jar \
   /opt/acquira/app/acquira-core.jar
```

### 4.3 Extract Playwright Driver (CRITICAL)

Spring Boot's fat JAR cannot extract the Playwright Node.js driver at runtime.
You must pre-extract it manually:

```bash
cd /opt/acquira/playwright-driver

# Extract driver-bundle from the fat JAR
jar xf /opt/acquira/app/acquira-core.jar BOOT-INF/lib/driver-bundle-1.40.0.jar

# Unzip the driver contents
unzip -o BOOT-INF/lib/driver-bundle-1.40.0.jar "driver/linux/*"

# Verify
ls -la driver/linux/
# Must show: node (91MB), playwright.sh, package/, LICENSE

# Verify node binary works
chmod +x driver/linux/node
driver/linux/node --version
# Should show: v18.x.x

# Cleanup
rm -rf BOOT-INF META-INF
```

### 4.4 Build & deploy frontend (as acquira)

```bash
cd /opt/acquira/source/frontend
npm install
npm run build
cp -r dist/* /opt/acquira/frontend/
```

> **"vite: Permission denied":** Run as root:
> ```bash
> chmod -R o+rX /opt/acquira/source/frontend/node_modules
> chmod -R +x /opt/acquira/source/frontend/node_modules/.bin/
> ```

---

## PHASE 5: Configure the Application

### 5.1 Create production config (as acquira or root)

```bash
mkdir -p /opt/acquira/config

cat > /opt/acquira/config/application-prod.properties << 'EOF'
# ════════════════════════════════════════════════════════════
#  Acquira — RHEL Production Configuration
# ════════════════════════════════════════════════════════════

# ─── Database ───
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/acquira_db?reWriteBatchedInserts=true
spring.datasource.username=acquira_user
spring.datasource.password=YOUR_STRONG_PASSWORD_HERE

# ─── Paths (change as needed) ───
app.logs.dir=/opt/acquira/logs
app.reports.dir=/opt/acquira/reports
app.data.dir=/opt/acquira/data

# ─── Logging ───
logging.file.name=/opt/acquira/logs/core.log
logging.level.root=WARN
logging.level.com.acquira=INFO
logging.level.org.hibernate=ERROR

# ─── SQL init (FIRST RUN: always → AFTER FIRST RUN: change to never) ───
spring.sql.init.mode=always

# ─── Spring Batch ───
spring.batch.jdbc.initialize-schema=always
spring.batch.jdbc.table-prefix=batch_

# ─── CORS (your actual domain) ───
app.cors.origins=https://yourdomain.com

# ─── PDF Engine ───
pdf.pool.size=2
pdf.chart.wait.ms=800
pdf.batch.data.threads=4
pdf.reports.dir=/opt/acquira/reports

# ─── Server ───
server.port=8081
EOF
```

### 5.2 Create systemd service (as root)

```bash
cat > /etc/systemd/system/acquira.service << 'EOF'
[Unit]
Description=Acquira Platform (Core + Batch + PDF)
After=network.target postgresql-15.service

[Service]
Type=simple
User=acquira
Group=acquira
WorkingDirectory=/opt/acquira/app

ExecStart=/usr/lib/jvm/java-21-openjdk/bin/java \
  -Xms1g \
  -Xmx4g \
  -XX:+UseG1GC \
  -Djava.io.tmpdir=/opt/acquira/data \
  -Dspring.profiles.active=prod \
  -Dspring.config.additional-location=file:/opt/acquira/config/ \
  -jar /opt/acquira/app/acquira-core.jar

Restart=on-failure
RestartSec=10
SuccessExitStatus=143

StandardOutput=journal
StandardError=journal
SyslogIdentifier=acquira

ProtectHome=no
PrivateTmp=false

Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
Environment=PLAYWRIGHT_JAVA_SRC=/opt/acquira/playwright-driver/driver/linux
Environment=PLAYWRIGHT_BROWSERS_PATH=/home/acquira/.cache/ms-playwright
Environment=PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

[Install]
WantedBy=multi-user.target
EOF
```

> **CRITICAL environment variables explained:**
>
> | Variable | Value | Purpose |
> |----------|-------|---------|
> | `PLAYWRIGHT_JAVA_SRC` | `/opt/acquira/playwright-driver/driver/linux` | Pre-extracted Node.js driver (skips broken fat-JAR extraction) |
> | `PLAYWRIGHT_BROWSERS_PATH` | `/home/acquira/.cache/ms-playwright` | Where Chromium browser is installed |
> | `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` | `1` | Prevents Playwright from trying to download Firefox/WebKit at startup |
> | `ProtectHome=no` | - | Allows access to `~/.cache/ms-playwright` (Chromium binary) |
> | `PrivateTmp=false` | - | Allows access to shared `/tmp` |

### 5.3 Configure Nginx (as root)

```bash
cat > /etc/nginx/conf.d/acquira.conf << 'EOF'
server {
    listen 80;
    server_name yourdomain.com www.yourdomain.com;

    root /opt/acquira/frontend;
    index index.html;

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml;
    gzip_min_length 1000;

    # API reverse proxy
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 512M;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    # React SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
EOF

sudo nginx -t
sudo systemctl restart nginx
```

---

## PHASE 6: SELinux (as root)

```bash
sudo setsebool -P httpd_can_network_connect 1
sudo semanage fcontext -a -t httpd_sys_content_t "/opt/acquira/frontend(/.*)?"
sudo semanage fcontext -a -t var_log_t "/opt/acquira/logs(/.*)?"
sudo semanage fcontext -a -t httpd_sys_rw_content_t "/opt/acquira/data(/.*)?"
sudo semanage fcontext -a -t httpd_sys_rw_content_t "/opt/acquira/reports(/.*)?"
sudo restorecon -Rv /opt/acquira/
```

> The output will show hundreds of "Relabeled" lines — this is normal and means it worked.

---

## PHASE 7: Start the Application

### 7.1 First startup (as root)

```bash
sudo systemctl daemon-reload
sudo systemctl start acquira
sudo journalctl -u acquira -f
```

**Success indicators:**

```
✓ Cached 6 fonts (629 KB) — served via page.route() to Chromium
CSS loaded: 28035 bytes (with PDF overrides appended)
✓ PDF Engine ready — 2 browser slots active
Started CoreApplication in XX seconds
```

### 7.2 Verify

```bash
# Service running?
sudo systemctl status acquira

# API responding?
curl -s http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password"}'

# Frontend?
curl -s http://localhost/ | head -5

# PDF reports directory?
ls -la /opt/acquira/reports/
```

### 7.3 After first successful startup — disable SQL re-init

```bash
sed -i 's/spring.sql.init.mode=always/spring.sql.init.mode=never/' \
  /opt/acquira/config/application-prod.properties
sudo systemctl restart acquira
```

### 7.4 Enable auto-start

```bash
sudo systemctl enable acquira
```

---

## PHASE 8: SSL/TLS (as root)

```bash
sudo dnf install certbot python3-certbot-nginx -y
sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com
sudo systemctl enable certbot-renew.timer
```

---

## PHASE 9: Database Backup

```bash
cat > /opt/acquira/backups/backup.sh << 'SCRIPT'
#!/bin/bash
BACKUP_DIR=/opt/acquira/backups
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
pg_dump -U acquira_user -h localhost acquira_db | gzip > ${BACKUP_DIR}/acquira_db_${TIMESTAMP}.sql.gz
find ${BACKUP_DIR} -name "acquira_db_*.sql.gz" -mtime +30 -delete
SCRIPT

chmod +x /opt/acquira/backups/backup.sh
(crontab -l 2>/dev/null; echo "0 2 * * * /opt/acquira/backups/backup.sh >> /opt/acquira/logs/backup.log 2>&1") | crontab -
```

---

## Redeployment (Manual Upload — No Git)

### Full redeploy

From Windows PowerShell:

```powershell
scp -r "C:\Users\sivag\Desktop\cms\Acquira\*" acquira@SERVER:/opt/acquira/source/
```

On server as acquira:

```bash
cd /opt/acquira/source
mvn clean install -DskipTests

cp acquira-core/target/acquira-core-1.0.0-SNAPSHOT.jar /opt/acquira/app/acquira-core.jar

cd frontend && npm install && npm run build
cp -r dist/* /opt/acquira/frontend/
```

As root:

```bash
# Re-extract Playwright driver if acquira-pdf changed
su - acquira -c "cd /opt/acquira/playwright-driver && rm -rf driver BOOT-INF META-INF && jar xf /opt/acquira/app/acquira-core.jar BOOT-INF/lib/driver-bundle-1.40.0.jar && unzip -o BOOT-INF/lib/driver-bundle-1.40.0.jar 'driver/linux/*' && chmod +x driver/linux/node && rm -rf BOOT-INF META-INF"

systemctl restart acquira
journalctl -u acquira -f
```

### Partial redeploy (single module)

```powershell
# Example: only PDF module changed
scp -r "C:\Users\sivag\Desktop\cms\Acquira\acquira-pdf\*" acquira@SERVER:/opt/acquira/source/acquira-pdf/
```

```bash
cd /opt/acquira/source/acquira-pdf  && mvn clean install -DskipTests
cd /opt/acquira/source/acquira-core && mvn clean package -DskipTests
cp target/acquira-core-1.0.0-SNAPSHOT.jar /opt/acquira/app/acquira-core.jar
```

### Frontend only (no restart needed)

```powershell
scp -r "C:\Users\sivag\Desktop\cms\Acquira\frontend\*" acquira@SERVER:/opt/acquira/source/frontend/
```

```bash
cd /opt/acquira/source/frontend && npm install && npm run build
cp -r dist/* /opt/acquira/frontend/
```

---

## Quick Reference

| Action | Command |
|--------|---------|
| Start | `sudo systemctl start acquira` |
| Stop | `sudo systemctl stop acquira` |
| Restart | `sudo systemctl restart acquira` |
| Live logs | `sudo journalctl -u acquira -f` |
| App log file | `tail -f /opt/acquira/logs/core.log` |
| Status | `sudo systemctl status acquira` |
| PDF reports | `ls -lh /opt/acquira/reports/2026-01/` |
| DB console | `psql -U acquira_user -h localhost acquira_db` |

---

## Troubleshooting

### App won't start — "Address already in use"

```bash
sudo lsof -i :8081
sudo kill <PID>
sudo systemctl start acquira
```

### App won't start — "Failed at step NAMESPACE"

The Java path is wrong or `ProtectSystem=strict` is blocking. Fix:

```bash
# Find real Java path
readlink -f $(which java)
# Update ExecStart in systemd service with the actual path
# Ensure ProtectHome=no and PrivateTmp=false
```

### PDF fails — "Failed to create driver"

The Playwright Node.js driver isn't pre-extracted. Fix:

```bash
cd /opt/acquira/playwright-driver
rm -rf driver BOOT-INF META-INF
jar xf /opt/acquira/app/acquira-core.jar BOOT-INF/lib/driver-bundle-1.40.0.jar
unzip -o BOOT-INF/lib/driver-bundle-1.40.0.jar "driver/linux/*"
chmod +x driver/linux/node
rm -rf BOOT-INF META-INF
```

Verify systemd has:

```ini
Environment=PLAYWRIGHT_JAVA_SRC=/opt/acquira/playwright-driver/driver/linux
Environment=PLAYWRIGHT_BROWSERS_PATH=/home/acquira/.cache/ms-playwright
Environment=PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
```

### PDF fails — "Playwright browsers not installed"

```bash
su - acquira
cd /opt/acquira/source/acquira-pdf
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

### Spring Batch error — "bad SQL grammar BATCH_JOB_INSTANCE"

Spring Batch tables are uppercase but PostgreSQL creates lowercase. Add to config:

```properties
spring.batch.jdbc.initialize-schema=always
spring.batch.jdbc.table-prefix=batch_
```

Also grant permissions:

```bash
sudo -u postgres psql -d acquira_db
```

```sql
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO acquira_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO acquira_user;
```

### OutOfMemoryError

```bash
# Edit systemd service, change -Xmx4g to -Xmx6g or -Xmx8g
sudo vi /etc/systemd/system/acquira.service
sudo systemctl daemon-reload && sudo systemctl restart acquira
```

### Maven build — "Cannot delete target/"

```bash
sudo pkill -f acquira || true
sudo rm -rf /opt/acquira/source/acquira-*/target
```

### npm/node — "command not found" as acquira user

Node installed via NVM under root. See Phase 1.6 for making it available system-wide.

### vite — "Permission denied"

```bash
# As root
chmod -R o+rX /opt/acquira/source/frontend/node_modules
chmod -R +x /opt/acquira/source/frontend/node_modules/.bin/
```

### 502 Bad Gateway from Nginx

```bash
curl http://localhost:8081/api/auth/login     # Backend running?
sudo setsebool -P httpd_can_network_connect 1  # SELinux blocking?
```

### Changing paths after deployment

```bash
vi /opt/acquira/config/application-prod.properties
# Change: app.logs.dir, app.reports.dir, app.data.dir, pdf.reports.dir
sudo mkdir -p /new/path && sudo chown acquira:acquira /new/path
sudo systemctl restart acquira
```

---

## Code Changes Required for RHEL Deployment

These changes were already made to the source code:

### 1. `acquira-pdf/pom.xml` — Added `driver-bundle` dependency

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>driver-bundle</artifactId>
    <version>1.40.0</version>
</dependency>
```

### 2. `acquira-core/pom.xml` — Added `requiresUnpack` for driver-bundle

```xml
<requiresUnpack>
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>driver-bundle</artifactId>
    </dependency>
</requiresUnpack>
```

### 3. `application.properties` — OS-aware configurable paths

```properties
app.logs.dir=${APP_LOGS_DIR:logs}
app.reports.dir=${APP_REPORTS_DIR:reports}
app.data.dir=${APP_DATA_DIR:data}
logging.file.name=${app.logs.dir}/core.log
pdf.reports.dir=${app.reports.dir}
pdf.pool.size=${PDF_POOL_SIZE:2}
pdf.chart.wait.ms=${PDF_CHART_WAIT:800}
```

### 4. `application-prod.properties` — RHEL production profile (NEW)

Overrides paths to `/opt/acquira/*` absolute paths.

### 5. PDF size optimization — CSS overrides appended at init

Removes invisible decorative CSS layers that were causing 15MB PDFs.
PDFs now generate at ~2-3MB.

### 6. Pool size reduced 8→2

Prevents OutOfMemoryError from 8 concurrent Chromium instances.
