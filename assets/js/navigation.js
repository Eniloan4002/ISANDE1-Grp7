// Shared application-shell behavior for every role portal.
const menuToggle = document.querySelector('.menu-toggle');
const sidebar = document.querySelector('.sidebar');
const appShell = document.querySelector('.app-shell');

menuToggle?.addEventListener('click', () => {
  if (window.innerWidth <= 700) {
    sidebar.classList.toggle('open');
    menuToggle.setAttribute('aria-expanded', String(sidebar.classList.contains('open')));
    return;
  }

  appShell.classList.toggle('sidebar-collapsed');
  menuToggle.setAttribute('aria-expanded', String(!appShell.classList.contains('sidebar-collapsed')));
});
