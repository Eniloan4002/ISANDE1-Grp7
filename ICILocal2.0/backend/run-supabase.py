#!/usr/bin/env python3
"""Run Spring Boot against persistent Supabase PostgreSQL; prompt privately for secrets."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import stat
import subprocess

BACKEND = Path(__file__).resolve().parent
CONFIG = BACKEND.parent.parent / '.env.supabase.json'
PIDFILE = BACKEND.parent.parent / '.env.supabase.pid'
HOST = 'aws-1-ap-southeast-2.pooler.supabase.com'
USERNAME = 'postgres.ncenqotkmstesigdccdd'
URL = f'jdbc:postgresql://{HOST}:5432/postgres?sslmode=require&connectTimeout=15&ApplicationName=ici-local'


def private_config():
    if CONFIG.is_symlink():
        raise RuntimeError('Private configuration must not be a symbolic link.')
    if CONFIG.exists():
        if os.name != 'nt' and stat.S_IMODE(CONFIG.stat().st_mode) & 0o077:
            raise RuntimeError('Private configuration permissions must be 600. Run chmod 600 .env.supabase.json from the repository root.')
        config = json.loads(CONFIG.read_text())
        if config.get('DB_URL') != URL or config.get('DB_USER') != USERNAME:
            raise RuntimeError('Private configuration targets a different database. Review it before continuing.')
        if len(base64.b64decode(config['JWT_SECRET'], validate=True)) < 32:
            raise RuntimeError('The private JWT key must contain at least 32 random bytes.')
        return config, False
    print('First connection to the persistent Supabase project.')
    print('Disable its Data API in Supabase before continuing; Spring Boot is the database access boundary.')
    if input('Have you disabled the Data API for this project? Type yes: ').strip().lower() != 'yes':
        raise RuntimeError('Setup cancelled before connecting. Disable the Data API, then rerun.')
    config = {'DB_URL': URL, 'DB_USER': USERNAME, 'JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(), 'JWT_TTL_MINUTES': '15'}
    # Atomic exclusive creation: no shell parsing or secret interpolation into commands.
    descriptor = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as handle:
        json.dump(config, handle, indent=2)
        handle.write('\n')
    return config, True


def admin_password():
    while True:
        value = getpass.getpass('Choose NEW app administrator password (at least 12 characters): ')
        if len(value) < 12 or len(value.encode('utf-8')) > 72:
            print('Use at least 12 characters and at most 72 UTF-8 bytes.')
        elif value != getpass.getpass('Confirm app administrator password: '):
            print('Passwords did not match.')
        else:
            return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bootstrap-admin', action='store_true', help='Create a missing linus_admin account; never resets an existing password.')
    parser.add_argument('--stop', action='store_true', help='Stop the verified Codex-managed background instance; Supabase records are retained')
    args = parser.parse_args()
    if args.stop:
        if not PIDFILE.exists() or not PIDFILE.read_text().strip().isdigit():
            raise RuntimeError('No managed backend PID file. Stop a normal terminal-run backend with Ctrl+C.')
        pid = int(PIDFILE.read_text().strip())
        command = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True)
        expected = str(BACKEND / 'target/ici-local-0.1.0-SNAPSHOT.jar')
        if expected not in command:
            raise RuntimeError('PID no longer identifies this backend; no signal sent.')
        os.kill(pid, signal.SIGTERM)
        PIDFILE.unlink()
        print('Backend shutdown requested. Supabase accounts and records are retained.')
        return 0
    with socket.socket() as check:
        check.settimeout(1)
        if check.connect_ex(('127.0.0.1', 8081)) == 0:
            raise RuntimeError('Backend port 8081 is occupied. For the managed background instance use: python3 run-supabase.py --stop. For a terminal-run instance use Ctrl+C in that terminal. Temporary records are not automatically copied to Supabase.')
    config, first = private_config()
    db_password = getpass.getpass('SUPABASE DATABASE password (chosen when you created the project): ')
    if not db_password:
        raise RuntimeError('Database password cannot be blank.')
    environment = os.environ.copy()
    environment.update(config)
    environment.update({'DB_PASSWORD': db_password, 'SPRING_DATASOURCE_URL': config['DB_URL'], 'SPRING_DATASOURCE_USERNAME': config['DB_USER'], 'SPRING_DATASOURCE_PASSWORD': db_password, 'SPRING_FLYWAY_URL': config['DB_URL'], 'SPRING_FLYWAY_USER': config['DB_USER'], 'SPRING_FLYWAY_PASSWORD': db_password, 'ICI_AUTH_SECRET': config['JWT_SECRET'], 'BOOTSTRAP_ADMIN_ENABLED': 'false', 'ICI_BOOTSTRAP_ENABLED': 'false', 'BOOTSTRAP_ADMIN_PASSWORD': '', 'ICI_BOOTSTRAP_PASSWORD': '', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '3', 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE': '1', 'ICI_MIGRATIONS_RECONCILE_LOCAL_HISTORY': 'false'})
    if args.bootstrap_admin:
        chosen_admin_password = admin_password()
        environment.update({'BOOTSTRAP_ADMIN_ENABLED': 'true', 'ICI_BOOTSTRAP_ENABLED': 'true', 'BOOTSTRAP_ADMIN_USERNAME': 'linus_admin', 'ICI_BOOTSTRAP_USERNAME': 'linus_admin', 'BOOTSTRAP_ADMIN_EMAIL': 'linus@example.invalid', 'ICI_BOOTSTRAP_EMAIL': 'linus@example.invalid', 'BOOTSTRAP_ADMIN_PASSWORD': chosen_admin_password, 'ICI_BOOTSTRAP_PASSWORD': chosen_admin_password})
    print('Starting persistent Supabase backend on 8081. Keep the frontend running separately.')
    print('Database and administrator passwords are NOT saved. The private JWT/config file is Git-ignored.')
    print('Flyway will create/validate the application tables. Temporary local records are not copied.')
    print('Use your existing staff login. --bootstrap-admin only provisions a missing linus_admin; it never resets passwords.')
    wrapper = 'mvnw.cmd' if os.name == 'nt' else './mvnw'
    result = subprocess.run([wrapper, 'clean', 'spring-boot:run'], cwd=BACKEND, env=environment)
    if result.returncode:
        print('Backend stopped. Correct the reported connection/migration error, then rerun. Existing account passwords are not reset by bootstrap.')
    return result.returncode


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (KeyboardInterrupt, EOFError):
        print('\nStopped. Supabase records persist.')
    except Exception as error:
        raise SystemExit(str(error))
