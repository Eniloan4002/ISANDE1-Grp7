// Shared role portal shell used by all role-based pages.
const portal = document.body.dataset.role;
const page = document.body.dataset.page;
const roleConfig = {
  registrar: { label: 'Registrar Staff', office: "Registrar's Office", initials: 'RS', items: [['submission.html', 'Enrollment Submission'], ['queue.html', 'Enrollment Queue'], ['evaluate.html', 'Evaluate Applications'], ['requirements.html', 'Requirements'], ['attendance.html', 'First-Week Attendance'], ['duplicate.html', 'Duplicate Records'], ['academic-records.html', 'Academic Records'], ['reporting-monitoring.html', 'Reporting & Monitoring']] },
  adviser: { label: 'Teacher Adviser', office: 'Teacher Adviser Portal', initials: 'TA', items: [['advisory.html', 'Advisory Class'], ['class-records.html', 'Class Records'], ['student-monitoring.html', 'Student Monitoring']] },
  teacher: { label: 'Subject Teacher', office: 'Subject Teacher Portal', initials: 'ST', items: [['grade-entry.html', 'Grade Entry'], ['class-list.html', 'Assigned Classes'], ['progress-reports.html', 'Grade Corrections']] },
  guidance: { label: 'Guidance Staff', office: 'Guidance Office', initials: 'GS', items: [['guidance.html', 'Guidance Records'], ['student-profiles.html', 'Student Profiles'], ['reports.html', 'Reports']] },
  admin: { label: 'System Administrator', office: 'System Administration', initials: 'SA', items: [['users.html', 'User Accounts'], ['system-settings.html', 'System Settings'], ['audit-log.html', 'Audit Log']] }
};
const config = roleConfig[portal];
const navItems = config.items.map(([href, label]) => `<a class="nav-item ${href === page ? 'active' : ''}" href="${href}">${label}</a>`).join('');
const alertIcon = '<svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const menuIcon = '<svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';
const main = document.querySelector('.main-content');
const shell = document.createElement('div');
shell.className = 'app-shell';
shell.innerHTML = `<aside class="sidebar" aria-label="${config.label} navigation"><a class="brand" href="../index.html"><span class="brand-mark">ICI</span><span class="brand-copy"><strong>ICI Local</strong><small>Student Information System</small></span></a><nav class="sidebar-nav"><p class="nav-label">${config.label}</p>${navItems}</nav></aside><div class="main-frame"><header class="topbar"><button class="icon-button menu-toggle" aria-label="Toggle navigation" aria-expanded="true">${menuIcon}</button><div class="institution"><span>ILIGAN COMPUTER INSTITUTE</span><small>${config.office}</small></div><div class="topbar-actions"><button class="icon-button notification" aria-label="Alerts">${alertIcon}<span>3</span></button><button class="profile-button" aria-label="Current role"><span class="avatar">${config.initials}</span><span class="profile-name">${config.label}</span></button></div></header></div>`;
shell.querySelector('.main-frame').append(main);
document.body.append(shell);
