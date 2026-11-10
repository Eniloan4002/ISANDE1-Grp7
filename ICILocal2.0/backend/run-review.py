#!/usr/bin/env python3
"""Start the disposable local app with a privately chosen administrator password."""
import getpass
import os
from pathlib import Path


def main():
    print('Temporary reviewer database: accounts and records disappear when stopped.')
    print('Administrator username: linus_admin')
    while True:
        password = getpass.getpass('Choose administrator password (12 characters minimum): ')
        if len(password) < 12 or len(password.encode('utf-8')) > 72:
            print('Password must have at least 12 characters and at most 72 UTF-8 bytes. Try again.')
            continue
        if password != getpass.getpass('Confirm password: '):
            print('Passwords did not match. Try again.')
            continue
        break
    os.environ.update({
        'BOOTSTRAP_ADMIN_ENABLED': 'true',
        'BOOTSTRAP_ADMIN_USERNAME': 'linus_admin',
        'BOOTSTRAP_ADMIN_EMAIL': 'linus@example.invalid',
        'BOOTSTRAP_ADMIN_PASSWORD': password,
    })
    os.chdir(Path(__file__).resolve().parent)
    os.execv('/bin/sh', ['sh', './run-local-postgres.sh'])


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nStartup cancelled.')
