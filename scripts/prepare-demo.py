#!/usr/bin/env python3
"""Create explicit, isolated demo setup through real APIs on a local running app."""
import argparse
from datetime import date
import getpass
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8081')
    parser.add_argument('--campus-code', default='ILIGAN')
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1', '::1') or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        parser.error('This demo tool accepts only a plain localhost HTTP base URL.')
    base = args.base_url.rstrip('/')
    token = None

    def request(path, method='GET', body=None, bearer=None):
        headers = {'Content-Type': 'application/json'}
        if bearer:
            headers['Authorization'] = 'Bearer ' + bearer
        req = urllib.request.Request(base + '/api' + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read()).get('detail', 'Request rejected')
            except (ValueError, AttributeError):
                detail = 'Request rejected'
            raise RuntimeError(f'{method} {path}: HTTP {error.code}: {detail}') from None

    print('Creates NEW demo catalogs, two sections, one existing student and a registrar. No records are deleted or overwritten.')
    username = input('Administrator username [linus_admin]: ').strip() or 'linus_admin'
    admin_password = getpass.getpass('Administrator password: ')
    while True:
        password = getpass.getpass('Choose demo registrar password (at least 12 characters): ')
        if len(password) < 12 or len(password.encode()) > 72:
            print('Use at least 12 characters and at most 72 UTF-8 bytes.')
            continue
        if password != getpass.getpass('Confirm demo registrar password: '):
            print('Passwords did not match.')
            continue
        break
    try:
        token = request('/auth/login', 'POST', {'username': username, 'password': admin_password})['accessToken']
        def api(path, method='GET', body=None):
            return request(path, method, body, token)
        ref = api('/intake/reference-data')
        campus = next((c for c in ref['campuses'] if c['code'] == args.campus_code), None)
        if not campus:
            raise RuntimeError('Selected campus is not available to this administrator.')
        program = next(p for p in ref['programs'] if p['code'] == 'STEM')
        stamp = str(time.time_ns())[-12:]
        year = date.today().year
        academic = api('/setup/academic-years', 'POST', {'label': f'Demo {stamp}', 'startsOn': f'{year}-01-01', 'endsOn': f'{year+1}-12-31'})
        term = api('/setup/terms', 'POST', {'academicYearId': academic['id'], 'code': 'DEMO', 'name': f'Demo term {stamp}', 'startsOn': f'{year}-01-01', 'endsOn': f'{year}-12-31'})
        registrar = f'demo_registrar_{stamp}'
        api('/admin/accounts', 'POST', {'username': registrar, 'email': registrar+'@example.invalid', 'fullName': 'Demo Registrar '+stamp, 'password': password, 'role': 'ROLE_REGISTRAR', 'campusIds': [campus['id']]})
        verified = request('/auth/login', 'POST', {'username': registrar, 'password': password})
        request('/auth/logout', 'POST', {}, verified['accessToken'])
        lrn = '9'+stamp[-11:]
        student = api('/students', 'POST', {'campusId': campus['id'], 'programId': program['id'], 'voucherCode': 'QVR', 'track': 'ACADEMIC', 'lrn': lrn, 'firstName': 'DemoAlex', 'lastName': 'Returning'+stamp, 'birthDate': '2009-02-12', 'active': True, 'address': {}, 'guardians': []})
        section_names = []
        for suffix, capacity in [('A', 1), ('B', 2)]:
            name = f'Demo {stamp} {suffix}'
            api('/sections', 'POST', {'campusId': campus['id'], 'termId': term['id'], 'programId': program['id'], 'name': name, 'gradeLevel': '11', 'room': 'Demo room '+suffix, 'capacity': capacity})
            section_names.append(name)
        print('\nDemo setup ready. Keep both app terminals running.')
        print('Registrar username:', registrar)
        print('Password: the one you chose above; it is not printed or saved.')
        print('Campus:', campus['name'])
        print('Academic year:', academic['label'])
        print('Term:', term['name'])
        print('Program: STEM / Academic / Grade 11')
        print('Sections:', ', '.join(section_names), '(capacities 1 and 2)')
        print('Existing student:', student.get('studentNumber', student.get('student_number', student['id'])))
        print('First name: DemoAlex | Last name: Returning'+stamp+' | DOB: 2009-02-12 | LRN:', lrn)
        print('Use that identity for a continuing/returnee applicant and duplicate-review demo. Capture other new applicants through Intake.')
        print('Dates are not institution-approved; configure attendance explicitly or demonstrate the visible unconfigured Present gate.')
    finally:
        if token:
            try:
                request('/auth/logout', 'POST', {}, token)
            except Exception:
                pass


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nDemo setup cancelled. Any completed creates remain; nothing was deleted.')
    except Exception as error:
        raise SystemExit(str(error))
