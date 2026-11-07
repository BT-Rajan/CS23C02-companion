#!/bin/sh
# One-shot: create the two database users, run migrations as the migrator, then grant the runtime user only what it needs.
set -eu
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"   # keeps the password out of the process list
until mysqladmin ping -h mysql -uroot --silent; do sleep 2; done
mysql -h mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS cs23c02 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'cs23c02_migrator'@'%' IDENTIFIED BY '$MIGRATOR_PASSWORD';
CREATE USER IF NOT EXISTS 'cs23c02_app'@'%' IDENTIFIED BY '$APP_DB_PASSWORD';
GRANT ALL ON cs23c02.* TO 'cs23c02_migrator'@'%';
SQL
alembic upgrade head
python -m app.cli print-grants --schema cs23c02 --user "'cs23c02_app'@'%'" | mysql -h mysql -uroot
echo "migrations and grants done"
