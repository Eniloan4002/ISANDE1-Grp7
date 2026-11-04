// Shared application-shell behavior for every role portal.
const menuToggle = document.querySelector('.menu-toggle');
const sidebar = document.querySelector('.sidebar');
const appShell = document.querySelector('.app-shell');
const mobileNavigation = window.matchMedia('(max-width: 700px)');

function syncNavigationState() {
  if (mobileNavigation.matches) {
    appShell.classList.remove('sidebar-collapsed');
    sidebar.classList.remove('open');
    menuToggle.setAttribute('aria-expanded', 'false');
    return;
  }

  sidebar.classList.remove('open');
  menuToggle.setAttribute('aria-expanded', String(!appShell.classList.contains('sidebar-collapsed')));
}

menuToggle?.addEventListener('click', () => {
  if (mobileNavigation.matches) {
    sidebar.classList.toggle('open');
    menuToggle.setAttribute('aria-expanded', String(sidebar.classList.contains('open')));
    return;
  }

  appShell.classList.toggle('sidebar-collapsed');
  menuToggle.setAttribute('aria-expanded', String(!appShell.classList.contains('sidebar-collapsed')));
});

mobileNavigation.addEventListener('change', syncNavigationState);
syncNavigationState();
