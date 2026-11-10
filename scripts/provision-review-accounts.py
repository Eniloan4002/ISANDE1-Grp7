#!/usr/bin/env python3
"""Provision/verify reviewer identities on an explicitly selected backend; no default passwords."""
import argparse
import getpass
import json
import re
import urllib.error
import urllib.parse
import urllib.request

ROLES = ('REGISTRAR', 'PRINCIPAL', 'ADVISER', 'TEACHER', 'GUIDANCE', 'CASHIER_REF')
ALL_CAMPUSES = ('ILIGAN', 'KAPATAGAN', 'BUGO')
TEAM_ACCOUNTS = (
    ('linus_admin', 'ADMIN', ALL_CAMPUSES),
    ('anton_admin', 'ADMIN', ALL_CAMPUSES),
    ('pau_admin', 'ADMIN', ALL_CAMPUSES),
    ('eli_admin', 'ADMIN', ALL_CAMPUSES),
    ('registrar_demo', 'REGISTRAR', ('ILIGAN',)),
    ('principal_demo', 'PRINCIPAL', ALL_CAMPUSES),
    ('adviser_demo', 'ADVISER', ('ILIGAN',)),
    ('teacher_demo', 'TEACHER', ('ILIGAN',)),
    ('guidance_demo', 'GUIDANCE', ('ILIGAN',)),
    ('cashier_demo', 'CASHIER_REF', ('ILIGAN',)),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='Backend origin, e.g. http://127.0.0.1:8081; use HTTPS for remote servers')
    parser.add_argument('--campus-code', action='append', help='Repeat for each campus to grant, e.g. ILIGAN')
    parser.add_argument('--prefix', default='review', help='Username prefix; never a password')
    parser.add_argument('--verify-only', action='store_true', help='Verify existing identities without creating accounts')
    parser.add_argument('--named-team', action='store_true', help='Provision the ten exact team/demo usernames and their documented campus scopes')
    parser.add_argument('--shared-new-password', action='store_true', help='Privately choose one password for missing demo accounts; existing passwords are never replaced')
    args = parser.parse_args()
    if args.named_team and args.campus_code:
        parser.error('--named-team uses its documented campus scopes; omit --campus-code.')
    if not args.named_team and not args.campus_code:
        parser.error('Supply --campus-code or --named-team.')
    if args.shared_new_password and args.verify_only:
        parser.error('--shared-new-password is for creation, not --verify-only.')
    origin = urllib.parse.urlsplit(args.base_url)
    if origin.username or origin.password or origin.query or origin.fragment or origin.path not in ('', '/'):
        parser.error('Use an origin without credentials, path, query or fragment.')
    if origin.scheme != 'https' and not (origin.scheme == 'http' and origin.hostname in ('localhost', '127.0.0.1', '::1')):
        parser.error('Remote backends require HTTPS; plain HTTP is allowed only on localhost.')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,60}', args.prefix):
        parser.error('Use an ASCII username prefix of at most 60 letters, digits, dots, underscores or hyphens.')
    base = args.base_url.rstrip('/')

    def call(path, data=None, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(), headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'Backend returned HTTP {error.code} for {path.split("?")[0]}; check credentials, account status and scope.') from None

    print('Target:', base)
    admin_name = input('Administrator username: ').strip()
    admin = call('/api/auth/login', {'username': admin_name, 'password': getpass.getpass('Administrator password: ')})
    if admin['user'].get('passwordChangeRequired'):
        raise RuntimeError('Replace the administrator temporary password in Account Security before running this tool.')
    token = admin['accessToken']
    try:
        reference = call('/api/admin/accounts/reference-data', token=token)
        available = {campus['code']: campus for campus in reference['campuses']}
        plan = TEAM_ACCOUNTS if args.named_team else tuple((args.prefix + '_' + role.lower(), role, tuple(args.campus_code)) for role in ROLES)
        required_codes = {code for _, _, codes in plan for code in codes}
        if not required_codes.issubset(available):
            raise RuntimeError('Choose active campuses within administrator scope. Available: ' + ', '.join(available))
        print('Account plan:')
        for username, role, codes in plan:
            print(' ', username, '/', role, '/', ', '.join(codes))
        shared_password = None
        if args.shared_new_password:
            shared_password = getpass.getpass('Choose password for missing demo accounts (at least 12 characters): ')
            if len(shared_password) < 12 or len(shared_password.encode()) > 72:
                raise RuntimeError('Use at least 12 characters and at most 72 UTF-8 bytes. No accounts were changed.')
            if shared_password != getpass.getpass('Confirm new demo password: '):
                raise RuntimeError('Passwords did not match. No accounts were changed.')
        verified_count = 0
        for username, role, codes in plan:
            campuses = sorted({available[code]['id'] for code in codes})
            required = {('ROLE_' + role, campus) for campus in campuses}
            found = call('/api/admin/accounts?' + urllib.parse.urlencode({'search': username, 'page': 0}), token=token)
            account = next((item for item in found['items'] if item['username'].lower() == username.lower()), None)
            page = 1
            while account is None and page * 25 < found['total']:
                found = call('/api/admin/accounts?' + urllib.parse.urlencode({'search': username, 'page': page}), token=token)
                account = next((item for item in found['items'] if item['username'].lower() == username.lower()), None)
                page += 1
            if account is None and args.verify_only:
                raise RuntimeError('Missing reviewer account: ' + username)
            same_admin = username.lower() == admin_name.lower()
            password = None
            if not same_admin:
                password = shared_password if account is None and shared_password else getpass.getpass(('Existing' if account else 'Choose new') + ' password for ' + username + ': ')
            if account is None:
                if len(password) < 12 or len(password.encode()) > 72:
                    raise RuntimeError('Use at least 12 characters and at most 72 UTF-8 bytes; rerun safely to continue.')
                if not shared_password and password != getpass.getpass('Confirm new password: '):
                    raise RuntimeError('Passwords did not match; no account was created for this role.')
                account = call('/api/admin/accounts', {'username': username, 'email': username + '@example.invalid', 'fullName': 'Team ' + username if role == 'ADMIN' else 'Demo ' + role.replace('_', ' ').title(), 'password': password, 'role': 'ROLE_' + role, 'campusIds': campuses}, token)
            actual = {(grant['role'], grant['campusId']) for grant in account['grants']}
            if not account['active'] or not required.issubset(actual):
                raise RuntimeError('Existing account has inactive or unexpected grants; review it in User Accounts: ' + username)
            signed = admin if same_admin else call('/api/auth/login', {'username': username, 'password': password})
            try:
                if signed['user'].get('passwordChangeRequired'):
                    raise RuntimeError('Complete the required password change in the app for ' + username + ', then rerun verification.')
                profile = call('/api/auth/me', token=signed['accessToken'])
                if not required.issubset({(grant['role'], grant['campusId']) for grant in profile['grants']}):
                    raise RuntimeError('Verified login has unexpected access: ' + username)
            finally:
                if not same_admin:
                    call('/api/auth/logout', {}, signed['accessToken'])
            password = None
            verified_count += 1
            print('Verified:', username, '/', role)
        shared_password = None
        print(f'All {verified_count} requested accounts verified. Existing passwords and grants were not overwritten.')
    finally:
        call('/api/auth/logout', {}, token)



if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, urllib.error.URLError) as error:
        raise SystemExit(str(error)) from None
