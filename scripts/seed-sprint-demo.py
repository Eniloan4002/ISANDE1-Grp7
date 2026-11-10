#!/usr/bin/env python3
"""Seed a small fictional ICI2-13–19 demo through the running backend, preserving existing records."""
import argparse
from datetime import date
import getpass
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / '.demo-sprint-seed.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8081')
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.base_url)
    if origin.scheme != 'http' or origin.hostname not in ('127.0.0.1', 'localhost', '::1') or origin.username or origin.password or origin.path not in ('', '/') or origin.query or origin.fragment:
        parser.error('Use a localhost backend origin; seed only through the app API.')
    base = args.base_url.rstrip('/')
    token = None

    def call(path, method='GET', body=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer '+token
        req = urllib.request.Request(base+'/api'+path, headers=headers, method=method, data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=90) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read()).get('detail', 'Request rejected')
            except (ValueError, AttributeError):
                detail = 'Request rejected'
            raise RuntimeError(f'{method} {path}: HTTP {error.code}: {detail}') from None

    username = input('App administrator username [linus_admin]: ').strip() or 'linus_admin'
    signed = call('/auth/login', 'POST', {'username': username, 'password': getpass.getpass('App administrator password: ')})
    token = signed['accessToken']
    manifest = {'baseUrl': base, 'applications': []}

    def save():
        # This file contains fictional demo IDs/links, no credentials.
        temporary = MANIFEST.with_suffix('.tmp')
        temporary.write_text(json.dumps(manifest, indent=2)+'\n')
        os.replace(temporary, MANIFEST)

    def show():
        print('\nDEMO READY: '+manifest['termName']+' / Iligan / STEM / Grade 11')
        for app in manifest['applications']:
            print(f"{app['name']}: {app['scenario']}")
            print('  http://127.0.0.1:5173'+app['path'])
        print('Section A: full (1/1). Section B: 3 seats available.')
        print('Sign in as registrar_demo and filter by the demo term. Dates/data are fictional.')
    try:
        if MANIFEST.exists():
            previous = json.loads(MANIFEST.read_text())
            if previous.get('baseUrl') == base and previous.get('complete'):
                years = call('/setup/academic-years')
                if any(y['id'] == previous.get('yearId') and y['label'] == previous.get('yearName') for y in years):
                    manifest = previous
                    print('Existing demo set found; leaving your current progress unchanged.')
                    show()
                    return
        ref = call('/intake/reference-data')
        shared_terms = [term for term in ref['terms'] if term['name'].startswith('SPRINT DEMO ')]
        if shared_terms:
            print('A shared sprint demo already exists. No records were added or reset.')
            for term in shared_terms:
                print('Use existing term:', term['name'])
            print('Sign in as registrar_demo and select that term in the app.')
            return
        campus = next(c for c in ref['campuses'] if c['code'] == 'ILIGAN')
        program = next(p for p in ref['programs'] if p['code'] == 'STEM')
        tag = secrets.token_hex(3).upper()
        year = date.today().year
        manifest.update({'tag': tag, 'yearName': 'Demo '+tag, 'termName': 'SPRINT DEMO '+tag, 'campusId': campus['id'], 'programId': program['id']})
        academic = call('/setup/academic-years', 'POST', {'label': manifest['yearName'], 'startsOn': f'{year}-06-01', 'endsOn': f'{year+1}-05-31'})
        manifest['yearId'] = academic['id']
        term = call('/setup/terms', 'POST', {'academicYearId': academic['id'], 'code': 'DEMO', 'name': manifest['termName'], 'startsOn': f'{year}-06-01', 'endsOn': f'{year}-12-31'})
        manifest['termId'] = term['id']
        save()
        print('Created demo year/term:', manifest['termName'], flush=True)
        call(f"/enrollment/attendance-window?campusId={campus['id']}&termId={term['id']}", 'PUT', {'startsOn': f'{year}-08-03', 'endsOn': f'{year}-08-07', 'orientationStartsOn': f'{year}-07-20', 'orientationEndsOn': f'{year}-07-20', 'version': 0, 'approvalNote': 'FICTIONAL sprint-review demo schedule only; not an approved ICI calendar.'})
        sections = []
        for suffix, capacity in [('A', 1), ('B', 3)]:
            sections.append(call('/sections', 'POST', {'campusId': campus['id'], 'termId': term['id'], 'programId': program['id'], 'name': 'DEMO '+tag+' STEM 11-'+suffix, 'gradeLevel': '11', 'room': 'Demo Room '+suffix, 'capacity': capacity}))
        manifest['sections'] = sections
        save()
        number = secrets.randbelow(100000000)
        def payload(first, last, month, index, category='new'):
            return {'campusId': campus['id'], 'termId': term['id'], 'programId': program['id'], 'category': category, 'firstName': first, 'lastName': last, 'birthDate': f'2009-{month:02d}-12', 'lrn': f'99{number:08d}{index:02d}', 'voucher': 'QVR', 'track': 'ACADEMIC', 'gradeLevel': '11', 'district': 'Demo Tibanga', 'transportRoute': 'Demo North', 'previousSchool': 'Fictional Demonstration School', 'email': f'demo{tag.lower()}{index}@example.invalid', 'guardians': [{'fullName': 'Demo Guardian '+last, 'relationship': 'Parent', 'phone': '09000000000', 'address': 'Fictional demo address', 'primaryContact': True}]}
        def detail(app):
            return call(f"/applications/{app['id']}/enrollment")
        def add(data, scenario, stage='submitted'):
            app = call('/intake/applications', 'POST', data)
            manifest['applications'].append({'id': app['id'], 'name': data['firstName']+' '+data['lastName'], 'scenario': scenario, 'path': f"/enrollment/applicants/{app['id']}"})
            save()
            if stage != 'submitted':
                if app['status'] != 'submitted':
                    raise RuntimeError('Unexpected duplicate while preparing '+data['firstName']+'; created records retained for review.')
                app = call(f"/intake/applications/{app['id']}/review", 'POST', {'version': app['version']})
            if stage in ('complete', 'ready', 'enrolled'):
                for item in detail(app)['checklist']['items']:
                    if item['mandatory']:
                        call(f"/applications/{app['id']}/requirements/{urllib.parse.quote(item['code'])}", 'PUT', {'status': 'submitted', 'notes': 'Fictional demo credential verified for sprint review.', 'version': item['version']})
            if stage in ('ready', 'enrolled'):
                call(f"/applications/{app['id']}/attendance/{year}-08-03", 'PUT', {'status': 'present', 'version': 0})
            if stage == 'enrolled':
                ready = detail(app)
                call(f"/applications/{app['id']}/confirm-enrollment", 'POST', {'version': ready['application']['version']})
            print('Prepared:', data['firstName'], data['lastName'], '/', scenario, flush=True)
            return app
        add(payload('Carlo', 'Bautista', 1, 1), 'New walk-in: open and Review and pre-enlist')
        add(payload('Maria', 'Santos', 2, 2), 'Pre-enlisted: finish the requirements checklist', 'reviewed')
        add(payload('Ana', 'Garcia', 3, 3), 'Documents complete: attendance still required', 'complete')
        miguel = add(payload('Miguel', 'Dela Cruz', 4, 4), 'Ready for your live Confirm enrollment action', 'ready')
        sofia_data = payload('Sofia', 'Reyes', 5, 5)
        sofia = add(sofia_data, 'Enrolled and assigned to the full section A', 'enrolled')
        enrolled = detail(sofia)['enrollment']
        call(f"/enrollments/{enrolled['id']}/section", 'PUT', {'sectionId': sections[0]['id'], 'version': enrolled['version']})
        daniel = add(payload('Daniel', 'Ramos', 6, 6), 'Enrolled/unassigned: try full section A, then assign to B', 'enrolled')
        existing = payload('Paolo', 'Mendoza', 7, 7, 'continuing')
        master = call('/students', 'POST', {**existing, 'voucherCode': 'QVR', 'address': {}, 'active': True})
        add(existing, 'Continuing student: resolve same-person case/link existing identity')
        add(sofia_data, 'Exact duplicate: reject incoming application, retain canonical Sofia')
        fuzzy_master = payload('Elena', 'Salazar', 8, 8)
        call('/students', 'POST', {**fuzzy_master, 'voucherCode': 'QVR', 'address': {}, 'active': True})
        fuzzy = {**fuzzy_master, 'firstName': 'Elenaa', 'lrn': None}
        add(fuzzy, 'Possible false positive: compare and mark Distinct people')
        for app in manifest['applications']:
            state = call(f"/intake/applications/{app['id']}")
            assert state['id'] == app['id']
        assert detail(miguel)['canConfirm'] is True
        assert detail(daniel)['enrollment']['sectionId'] is None
        assert call(f"/sections/{sections[0]['id']}")['available'] == 0
        manifest.update({'existingStudentId': master['id'], 'complete': True})
        save()
        show()
    finally:
        if token:
            call('/auth/logout', 'POST', {})


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nStopped. Completed creates are preserved; no existing records were deleted.')
    except Exception as error:
        raise SystemExit(str(error))
