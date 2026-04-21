# Acquira Platform — RHEL Installation Guide

This guide provides step-by-step instructions to install and run the **Acquira Merchant Analytics Platform** from scratch on a Red Hat Enterprise Linux (RHEL 8/9) server.

**Estimated time:** 45 – 90 minutes

---

## Table of Contents

1. [System Requirements](#1-system-requirements)
2. [Pre-Installation Checklist](#2-pre-installation-checklist)
3. [Install System Dependencies](#3-install-system-dependencies)
4. [Install Java 21](#4-install-java-21)
5. [Install Maven](#5-install-maven)
6. [Install Node.js 20](#6-install-nodejs-20)
7. [Install PostgreSQL 16](#7-install-postgresql-16)
8. [Create Application User and Directories](#8-create-application-user-and-directories)
9. [Clone the Repository](#9-clone-the-repository)
10. [Configure Environment Variables](#10-configure-environment-variables)
11. [Build the Backend](#11-build-the-backend)
12. [Build the Frontend](#12-build-the-frontend)
13. [Create Systemd Services](#13-create-systemd-services)
14. [Open Firewall Ports](#14-open-firewall-ports)
15. [Start Services](#15-start-services)
16. [Verify Installation](#16-verify-installation)
17. [Nginx Reverse Proxy (Optional)](#17-nginx-reverse-proxy-optional)
18. [Troubleshooting](#18-troubleshooting)
19. [Maintenance & Operations](#19-maintenance--operations)

---

## 1. System Requirements

| Component | Minimum | Recommended |
|---|---|---|
| OS | RHEL 8 or 9 (or CentOS Stream, Rocky Linux, AlmaLinux) | RHEL 9 |
| CPU | 4 cores | 8 cores |
| RAM | 8 GB | 16 GB |
| Disk | 50 GB SSD | 200 GB SSD |
| Network | 1 Gbps | 1 Gbps |
| Ports open | 5432, 8081, 8085 (internal); 80/443 (external) | Same |

### Software Stack

- **Java 21** (OpenJDK / Temurin)
- **Maven 3.9+**
- **Node.js 20 LTS**
- **PostgreSQL 16**
- **nginx** (optional, for reverse proxy)
- **systemd** (for service management)

---

## 2. Pre-Installation Checklist

Before starting, ensure you have:

- [ ] Root or `sudo` access on the RHEL server
- [ ] Internet access to download packages (or a configured internal mirror)
- [ ] Git credentials (SSH key or personal access token) for cloning the repository
- [ ] Database password prepared (strong, stored in a password manager)
- [ ] JWT secret prepared (32+ random characters)
- [ ] Encryption key prepared (exactly 32 random characters)
- [ ] DNS / hostname configured if exposing externally

Generate strong secrets now (save for later use):

```bash
# JWT secret (base64, 48 characters)
openssl rand -base64 36

# Encryption key (exactly 32 characters)
openssl rand -hex 16
```

---

## 3. Install System Dependencies

Update the base system and install tools used throughout the install:

```bash
sudo dnf update -y
sudo dnf install -y wget curl tar gzip unzip git vim firewalld policycoreutils-python-utils
```

Enable the firewall service (we'll configure rules later):

```bash
sudo systemctl enable --now firewalld
```

---

## 4. Install Java 21

Install Eclipse Temurin JDK 21:

### Option A — From Temurin repository (recommended)

```bash
sudo tee /etc/yum.repos.d/adoptium.repo <<'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF

sudo dnf install -y temurin-21-jdk
```

### Option B — From OpenJDK repo

```bash
sudo dnf install -y java-21-openjdk java-21-openjdk-devel
```

### Verify

```bash
java -version
# Should print: openjdk version "21.x.x"

javac -version
# Should print: javac 21.x.x
```

### Set JAVA_HOME globally

```bash
sudo tee /etc/profile.d/java.sh <<'EOF'
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk
export PATH=$JAVA_HOME/bin:$PATH
EOF
sudo chmod +x /etc/profile.d/java.sh
source /etc/profile.d/java.sh

echo $JAVA_HOME
# Should print the JDK path
```

> **Note:** If you used Option B, `JAVA_HOME` is typically `/usr/lib/jvm/java-21-openjdk`. Adjust as needed.

---

## 5. Install Maven

Download and install Maven 3.9.x:

```bash
cd /opt
sudo wget https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
sudo tar -xzf apache-maven-3.9.9-bin.tar.gz
sudo ln -sfn /opt/apache-maven-3.9.9 /opt/maven
sudo rm apache-maven-3.9.9-bin.tar.gz

sudo tee /etc/profile.d/maven.sh <<'EOF'
export M2_HOME=/opt/maven
export PATH=$M2_HOME/bin:$PATH
EOF
sudo chmod +x /etc/profile.d/maven.sh
source /etc/profile.d/maven.sh

mvn -version
# Should print: Apache Maven 3.9.9 ... Java version: 21
```

---

## 6. Install Node.js 20

Install Node.js from NodeSource:

```bash
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

node --version   # should print v20.x.x
npm --version    # should print 10.x.x
```

---

## 7. Install PostgreSQL 16

### Install the repository and server

```bash
sudo dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-$(rpm -E %{rhel})-x86_64/pgdg-redhat-repo-latest.noarch.rpm

# Disable the built-in PostgreSQL module (RHEL 8/9 ships with an older version)
sudo dnf -qy module disable postgresql

sudo dnf install -y postgresql16-server postgresql16-contrib
```

### Initialize the database cluster

```bash
sudo /usr/pgsql-16/bin/postgresql-16-setup initdb
sudo systemctl enable --now postgresql-16
```

### Configure PostgreSQL to listen on port 5432 and accept local connections

Edit `postgresql.conf`:

```bash
sudo vim /var/lib/pgsql/16/data/postgresql.conf
```

Set:
```conf
listen_addresses = 'localhost'
port = 5432
max_connections = 200
shared_buffers = 2GB
```

Edit `pg_hba.conf` to use password authentication:

```bash
sudo vim /var/lib/pgsql/16/data/pg_hba.conf
```

Change these lines:
```conf
# TYPE  DATABASE        USER            ADDRESS                 METHOD
local   all             all                                     md5
host    all             all             127.0.0.1/32            md5
host    all             all             ::1/128                 md5
```

Restart PostgreSQL:

```bash
sudo systemctl restart postgresql-16
```

### Create the Acquira database and user

```bash
sudo -u postgres psql <<EOF
CREATE USER acquira_user WITH PASSWORD 'ReplaceWithStrongPassword';
CREATE DATABASE acquira_db OWNER acquira_user ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE acquira_db TO acquira_user;
\c acquira_db
GRANT ALL ON SCHEMA public TO acquira_user;
EOF
```

Verify login:

```bash
psql -h 127.0.0.1 -U acquira_user -d acquira_db -c 'SELECT version();'
# Enter the password you set above
```

---

## 8. Create Application User and Directories

Create a dedicated OS user to run Acquira (never run as root):

```bash
sudo useradd -r -m -d /opt/acquira -s /bin/bash acquira

sudo mkdir -p /opt/acquira/{app,logs,reports,data,uploads,backups}
sudo chown -R acquira:acquira /opt/acquira
```

Directory structure:

```
/opt/acquira/
├── app/        # Application JARs and built frontend
├── logs/       # Spring Boot log files
├── reports/    # Generated PDF reports
├── data/       # Runtime data files
├── uploads/    # Uploaded Excel/CSV files
└── backups/    # Database backups
```

---

## 9. Clone the Repository

Switch to the `acquira` user and clone the code:

```bash
sudo -u acquira -i
cd /opt/acquira

# Clone via HTTPS with personal access token
git clone https://<YOUR_TOKEN>@<YOUR_GIT_HOST>/your-org/acquira.git source

# OR via SSH (if you've configured the server's SSH key on your Git provider)
git clone git@<YOUR_GIT_HOST>:your-org/acquira.git source

cd source

# Switch to the desired branch (e.g., main or feature/microservices)
git checkout main
```

> **Important:** The `.gitignore` must include `src/` folders correctly (leading slash `/src/` only, NOT `src/`) so that all module sources are committed.

---

## 10. Configure Environment Variables

Create an environment file with your secrets:

```bash
sudo mkdir -p /etc/acquira
sudo tee /etc/acquira/acquira.env <<'EOF'
# ─── Database ───
DB_URL=jdbc:postgresql://127.0.0.1:5432/acquira_db?reWriteBatchedInserts=true
DB_USERNAME=acquira_user
DB_PASSWORD=ReplaceWithStrongPassword

# ─── Security ───
JWT_SECRET_KEY=ReplaceWith48PlusCharRandomBase64String
APP_ENCRYPTION_KEY=Replace32CharExactly!!!!!!!!!!!

# ─── CORS ───
CORS_ORIGINS=http://your-domain.com,https://your-domain.com

# ─── Spring Profile ───
SPRING_PROFILES_ACTIVE=prod

# ─── Runtime Paths ───
APP_LOGS_DIR=/opt/acquira/logs
APP_REPORTS_DIR=/opt/acquira/reports
APP_DATA_DIR=/opt/acquira/data

# ─── SQL Init (set to 'never' after first successful boot) ───
SQL_INIT_MODE=always

# ─── PDF Engine Tuning ───
PDF_POOL_SIZE=2
PDF_CHART_WAIT=300
PDF_DATA_THREADS=4

# ─── Optional: Microsoft SSO ───
SSO_MS_ENABLED=false
SSO_MS_CLIENT_ID=
SSO_MS_CLIENT_SECRET=
SSO_MS_TENANT_ID=common
SSO_MS_REDIRECT_URI=http://your-domain.com/auth/sso/callback
EOF

sudo chmod 640 /etc/acquira/acquira.env
sudo chown root:acquira /etc/acquira/acquira.env
```

> **Critical:** Replace `ReplaceWithStrongPassword`, `ReplaceWith48PlusCharRandomBase64String`, and `Replace32CharExactly!!!!!!!!!!!` with the real secrets from step 2. Never commit this file to Git.

---

## 11. Build the Backend

As the `acquira` user, build the multi-module Spring Boot project:

```bash
sudo -u acquira -i
cd /opt/acquira/source

mvn clean package -DskipTests
```

This takes 3-10 minutes depending on network/CPU. You should see `BUILD SUCCESS` with all 5 modules packaged.

Copy the runnable JARs into the app directory:

```bash
cp acquira-core/target/acquira-core-*.jar /opt/acquira/app/acquira-core.jar
cp acquira-batch/target/acquira-batch-*.jar /opt/acquira/app/acquira-batch.jar
# acquira-pdf, acquira-ai, acquira-common are library JARs and already included inside acquira-core
```

---

## 12. Build the Frontend

```bash
cd /opt/acquira/source/frontend

npm install
npm run build
```

This produces static assets in `dist/`. Copy them to the serving directory:

```bash
rm -rf /opt/acquira/app/frontend
mv dist /opt/acquira/app/frontend
```

> Later, nginx will serve `/opt/acquira/app/frontend` directly.

---

## 13. Create Systemd Services

### 13a. acquira-core service

```bash
sudo tee /etc/systemd/system/acquira-core.service <<'EOF'
[Unit]
Description=Acquira Core (Auth, Analytics, Dashboards, Admin)
After=network-online.target postgresql-16.service
Wants=network-online.target postgresql-16.service

[Service]
Type=simple
User=acquira
Group=acquira
WorkingDirectory=/opt/acquira/app
EnvironmentFile=/etc/acquira/acquira.env
ExecStart=/usr/bin/java -Xms1g -Xmx4g \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
  -Dfile.encoding=UTF-8 \
  -jar /opt/acquira/app/acquira-core.jar
SuccessExitStatus=143
TimeoutStopSec=30
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=acquira-core

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/acquira/logs /opt/acquira/reports /opt/acquira/data /opt/acquira/uploads /opt/acquira/backups
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
```

### 13b. acquira-batch service

```bash
sudo tee /etc/systemd/system/acquira-batch.service <<'EOF'
[Unit]
Description=Acquira Batch (File Ingestion, Spring Batch Jobs)
After=network-online.target postgresql-16.service
Wants=network-online.target postgresql-16.service

[Service]
Type=simple
User=acquira
Group=acquira
WorkingDirectory=/opt/acquira/app
EnvironmentFile=/etc/acquira/acquira.env
ExecStart=/usr/bin/java -Xms512m -Xmx3g \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
  -Dfile.encoding=UTF-8 \
  -jar /opt/acquira/app/acquira-batch.jar
SuccessExitStatus=143
TimeoutStopSec=30
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=acquira-batch

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/acquira/logs /opt/acquira/data /opt/acquira/uploads
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
```

Reload systemd and enable services:

```bash
sudo systemctl daemon-reload
sudo systemctl enable acquira-core acquira-batch
```

---

## 14. Open Firewall Ports

Acquira uses these ports:

| Port | Purpose | External? |
|---|---|---|
| 5432 | PostgreSQL | No (localhost only) |
| 8081 | acquira-core API | Only if nginx is not used |
| 8085 | acquira-batch API | No (internal only) |
| 80 | HTTP (via nginx) | Yes |
| 443 | HTTPS (via nginx) | Yes |

### If exposing Spring Boot directly (no nginx):

```bash
sudo firewall-cmd --permanent --add-port=8081/tcp
sudo firewall-cmd --reload
```

### If using nginx reverse proxy (recommended):

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

### SELinux considerations

RHEL enforces SELinux. Allow Spring Boot to bind to port 8081:

```bash
sudo semanage port -a -t http_port_t -p tcp 8081
sudo semanage port -a -t http_port_t -p tcp 8085
```

Allow the systemd service to connect to the network:

```bash
sudo setsebool -P httpd_can_network_connect 1
```

---

## 15. Start Services

```bash
sudo systemctl start acquira-core
sudo systemctl start acquira-batch

# Check status
sudo systemctl status acquira-core --no-pager
sudo systemctl status acquira-batch --no-pager
```

Monitor startup logs:

```bash
sudo journalctl -u acquira-core -f
# Press Ctrl+C to exit
```

Wait until you see:
```
Started AcquiraApplication in XX seconds
Tomcat started on port(s): 8081 (http)
```

---

## 16. Verify Installation

### 16a. Backend health check

```bash
curl -i http://localhost:8081/actuator/health
# Should return: {"status":"UP"}
```

### 16b. Database schema loaded?

```bash
psql -h 127.0.0.1 -U acquira_user -d acquira_db -c '\dt'
# Should list dozens of tables (dim_merchant, fact_transaction, stg_trnx_raw, ref_country, etc.)
```

### 16c. Seed data loaded?

```bash
psql -h 127.0.0.1 -U acquira_user -d acquira_db -c 'SELECT COUNT(*) FROM ref_country;'
# Should return ~225
```

### 16d. After first successful boot, disable SQL init

Edit `/etc/acquira/acquira.env` and set:

```conf
SQL_INIT_MODE=never
```

Then restart:

```bash
sudo systemctl restart acquira-core
```

This prevents re-running `schema.sql` / `data.sql` on every boot.

---

## 17. Nginx Reverse Proxy (Optional)

Serve the frontend and proxy API calls to Spring Boot.

### Install nginx

```bash
sudo dnf install -y nginx
sudo systemctl enable --now nginx
```

### Configure the virtual host

```bash
sudo tee /etc/nginx/conf.d/acquira.conf <<'EOF'
server {
    listen 80;
    server_name your-domain.com;

    # Maximum upload size (must match Spring Boot's multipart.max-file-size)
    client_max_body_size 2048M;

    # Timeouts for long-running operations (PDF generation, large uploads)
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
    proxy_connect_timeout 60s;

    # Frontend static assets
    root /opt/acquira/app/frontend;
    index index.html;

    # React Router — all unmatched routes serve index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Proxy /api to acquira-core
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Gzip compression for frontend assets
    gzip on;
    gzip_types text/css text/javascript application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    # Cache static assets aggressively
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
EOF

sudo nginx -t
sudo systemctl reload nginx
```

### Enable HTTPS (Let's Encrypt)

```bash
sudo dnf install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
# Follow prompts; certbot will auto-configure HTTPS
```

---

## 18. Troubleshooting

### Service won't start

```bash
# View last 100 log lines
sudo journalctl -u acquira-core -n 100 --no-pager

# Follow live logs
sudo journalctl -u acquira-core -f
```

Common causes:
- Wrong `DB_PASSWORD` in `/etc/acquira/acquira.env`
- PostgreSQL not running (`sudo systemctl status postgresql-16`)
- Port 8081 already in use (`sudo ss -tlnp | grep 8081`)
- Java not found (`sudo -u acquira java -version`)

### Port already in use

```bash
sudo ss -tlnp | grep -E '8081|8085'
# Kill the process or change the port in application.properties
```

### Database connection refused

```bash
# Check PostgreSQL is listening
sudo ss -tlnp | grep 5432

# Test connection manually
psql -h 127.0.0.1 -U acquira_user -d acquira_db

# Check pg_hba.conf allows password auth for localhost
sudo cat /var/lib/pgsql/16/data/pg_hba.conf | grep -v '^#'
```

### SELinux blocking something

```bash
# View recent SELinux denials
sudo ausearch -m AVC -ts recent

# If denials relate to acquira, generate a policy:
sudo ausearch -m AVC -ts recent | audit2allow -M acquira
sudo semodule -i acquira.pp
```

### Frontend shows connection refused

- Verify acquira-core is running on 8081 (`curl http://localhost:8081/actuator/health`)
- Check nginx config (`sudo nginx -t`) and reload (`sudo systemctl reload nginx`)
- Confirm `CORS_ORIGINS` in `/etc/acquira/acquira.env` includes your frontend URL

### Out of memory (OOM)

Increase Java heap in `/etc/systemd/system/acquira-core.service`:
```
ExecStart=/usr/bin/java -Xms2g -Xmx8g ...
```
Then reload:
```bash
sudo systemctl daemon-reload
sudo systemctl restart acquira-core
```

### Maven build fails with "release version 21 not supported"

You're running an old Java. Re-check `java -version` and `JAVA_HOME`. Fix by re-sourcing `/etc/profile.d/java.sh` or re-installing JDK 21.

---

## 19. Maintenance & Operations

### Daily database backup

Create `/opt/acquira/backups/backup.sh`:

```bash
sudo -u acquira tee /opt/acquira/backups/backup.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE=/opt/acquira/backups/acquira_db_${TIMESTAMP}.sql.gz
export PGPASSWORD='ReplaceWithStrongPassword'
pg_dump -h 127.0.0.1 -U acquira_user -d acquira_db | gzip > "${BACKUP_FILE}"
# Keep only last 14 days
find /opt/acquira/backups -name 'acquira_db_*.sql.gz' -mtime +14 -delete
EOF
sudo -u acquira chmod +x /opt/acquira/backups/backup.sh
```

Schedule via cron (as `acquira` user):

```bash
sudo -u acquira crontab -e
```

Add:
```
0 2 * * * /opt/acquira/backups/backup.sh >> /opt/acquira/logs/backup.log 2>&1
```

### Deploying a new version

```bash
sudo -u acquira -i
cd /opt/acquira/source

git pull
mvn clean package -DskipTests
cp acquira-core/target/acquira-core-*.jar /opt/acquira/app/acquira-core.jar
cp acquira-batch/target/acquira-batch-*.jar /opt/acquira/app/acquira-batch.jar

cd frontend
npm install
npm run build
rm -rf /opt/acquira/app/frontend
mv dist /opt/acquira/app/frontend

exit  # back to sudo user

sudo systemctl restart acquira-core acquira-batch
sudo systemctl reload nginx
```

### Log rotation

Systemd's journald handles log rotation automatically. To control size, edit `/etc/systemd/journald.conf`:

```conf
SystemMaxUse=2G
SystemKeepFree=1G
MaxRetentionSec=30day
```

Restart journald:
```bash
sudo systemctl restart systemd-journald
```

For Spring Boot's own `logs/*.log` files, configure log rotation:

```bash
sudo tee /etc/logrotate.d/acquira <<'EOF'
/opt/acquira/logs/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0640 acquira acquira
}
EOF
```

### Monitoring

Simple health check (add to monitoring system):

```bash
curl -fs http://localhost:8081/actuator/health | grep -q '"status":"UP"'
```

### Common service operations

```bash
# Status
sudo systemctl status acquira-core

# Restart
sudo systemctl restart acquira-core

# Stop
sudo systemctl stop acquira-core

# View logs (last 200 lines)
sudo journalctl -u acquira-core -n 200 --no-pager

# Live logs
sudo journalctl -u acquira-core -f
```

---

## Appendix A — Quick Install Script

For convenience, here's a condensed one-shot script (review before running):

```bash
#!/usr/bin/env bash
# install-acquira.sh — one-shot installer for RHEL 9
# Usage: sudo bash install-acquira.sh
set -euo pipefail

# 1. System packages
dnf update -y
dnf install -y wget curl tar gzip unzip git vim firewalld policycoreutils-python-utils

# 2. Java 21
tee /etc/yum.repos.d/adoptium.repo <<'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
dnf install -y temurin-21-jdk

# 3. Maven
cd /opt
wget -q https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
tar -xzf apache-maven-3.9.9-bin.tar.gz
ln -sfn /opt/apache-maven-3.9.9 /opt/maven
rm apache-maven-3.9.9-bin.tar.gz

# 4. Node.js 20
curl -fsSL https://rpm.nodesource.com/setup_20.x | bash -
dnf install -y nodejs

# 5. PostgreSQL 16
dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-$(rpm -E %{rhel})-x86_64/pgdg-redhat-repo-latest.noarch.rpm
dnf -qy module disable postgresql
dnf install -y postgresql16-server postgresql16-contrib
/usr/pgsql-16/bin/postgresql-16-setup initdb
systemctl enable --now postgresql-16

# 6. User + directories
useradd -r -m -d /opt/acquira -s /bin/bash acquira || true
mkdir -p /opt/acquira/{app,logs,reports,data,uploads,backups}
chown -R acquira:acquira /opt/acquira

# 7. Environment stub (EDIT THIS MANUALLY)
mkdir -p /etc/acquira
cat > /etc/acquira/acquira.env <<'EOF'
DB_URL=jdbc:postgresql://127.0.0.1:5432/acquira_db?reWriteBatchedInserts=true
DB_USERNAME=acquira_user
DB_PASSWORD=CHANGE_ME
JWT_SECRET_KEY=CHANGE_ME_48_CHARS
APP_ENCRYPTION_KEY=CHANGE_ME_32_CHARS
CORS_ORIGINS=http://localhost
SPRING_PROFILES_ACTIVE=prod
APP_LOGS_DIR=/opt/acquira/logs
APP_REPORTS_DIR=/opt/acquira/reports
APP_DATA_DIR=/opt/acquira/data
SQL_INIT_MODE=always
PDF_POOL_SIZE=2
PDF_CHART_WAIT=300
PDF_DATA_THREADS=4
EOF
chmod 640 /etc/acquira/acquira.env
chown root:acquira /etc/acquira/acquira.env

# 8. Firewall
systemctl enable --now firewalld
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload

echo ""
echo "==================================================================="
echo " Base install complete."
echo ""
echo " NEXT STEPS (manual):"
echo "  1. Edit /etc/acquira/acquira.env and replace CHANGE_ME values"
echo "  2. Create database:"
echo "     sudo -u postgres psql -c \"CREATE USER acquira_user WITH PASSWORD '...';\""
echo "     sudo -u postgres psql -c \"CREATE DATABASE acquira_db OWNER acquira_user;\""
echo "  3. Configure /var/lib/pgsql/16/data/pg_hba.conf (md5 for localhost)"
echo "  4. Restart PostgreSQL: systemctl restart postgresql-16"
echo "  5. Clone repo:"
echo "     sudo -u acquira -i"
echo "     cd /opt/acquira && git clone <your-repo-url> source"
echo "  6. Build:"
echo "     cd source && mvn clean package -DskipTests"
echo "     cp acquira-core/target/*.jar /opt/acquira/app/acquira-core.jar"
echo "     cd frontend && npm install && npm run build"
echo "     mv dist /opt/acquira/app/frontend"
echo "  7. Create systemd services (see INSTALL_RHEL.md section 13)"
echo "  8. Start services:"
echo "     systemctl enable --now acquira-core acquira-batch"
echo "==================================================================="
```

---

## Appendix B — Port Reference

| Port | Service | Listening address | External? |
|---|---|---|---|
| 5432 | PostgreSQL 16 | 127.0.0.1 | No |
| 8081 | acquira-core (Spring Boot) | 0.0.0.0 | Only if no nginx |
| 8085 | acquira-batch (Spring Boot) | 0.0.0.0 | No |
| 80 | nginx HTTP | 0.0.0.0 | Yes |
| 443 | nginx HTTPS | 0.0.0.0 | Yes |
| 5173 | Vite dev server (dev only) | — | N/A on prod |

---

## Appendix C — File Locations

| Path | Purpose |
|---|---|
| `/opt/acquira/source` | Git clone of the repository |
| `/opt/acquira/app/acquira-core.jar` | Main application JAR |
| `/opt/acquira/app/acquira-batch.jar` | Batch processor JAR |
| `/opt/acquira/app/frontend/` | Built React frontend (served by nginx) |
| `/opt/acquira/logs/` | Application log files |
| `/opt/acquira/reports/` | Generated PDF reports |
| `/opt/acquira/data/` | Runtime data files |
| `/opt/acquira/uploads/` | Uploaded Excel/CSV files |
| `/opt/acquira/backups/` | Database dumps |
| `/etc/acquira/acquira.env` | Environment variables (secrets) |
| `/etc/systemd/system/acquira-core.service` | Core service definition |
| `/etc/systemd/system/acquira-batch.service` | Batch service definition |
| `/etc/nginx/conf.d/acquira.conf` | nginx reverse proxy config |
| `/var/lib/pgsql/16/data/` | PostgreSQL data directory |

---

## Support

For issues not covered here:

1. Check application logs: `sudo journalctl -u acquira-core -n 200`
2. Check PostgreSQL logs: `sudo tail -100 /var/lib/pgsql/16/data/log/*.log`
3. Check nginx logs: `sudo tail -100 /var/log/nginx/error.log`
4. Review troubleshooting section above
5. Contact platform team with log excerpts

---

**Document version:** 1.0
**Last updated:** 2026-04-21
**Applies to:** Acquira Platform v1.0.0-SNAPSHOT on RHEL 8/9
