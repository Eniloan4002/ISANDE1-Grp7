// Enrollment Queue demo interactions. No server or persistent data is used.
const search = document.querySelector('#queue-search');
const status = document.querySelector('#status-filter');
const program = document.querySelector('#program-filter');
const rows = [...document.querySelectorAll('#queue-body tr')];
const resultCount = document.querySelector('#result-count');
function filterQueue() { let visible = 0; const term = search.value.toLowerCase(); rows.forEach(row => { const show = row.textContent.toLowerCase().includes(term) && (status.value === 'all' || row.dataset.status === status.value) && (program.value === 'all' || row.dataset.program === program.value); row.hidden = !show; if (show) visible++ }); resultCount.textContent = `Showing ${visible} of 12 applicants` }
[search, status, program].forEach(item => item.addEventListener('input', filterQueue));
const modal = document.querySelector('#walkin-modal');
document.querySelector('[data-modal-open]').addEventListener('click', () => modal.classList.add('open'));
document.querySelectorAll('.modal-close').forEach(button => button.addEventListener('click', () => modal.classList.remove('open')));
modal.addEventListener('click', event => { if (event.target === modal) modal.classList.remove('open') });
const toast = document.querySelector('#toast'); let timer; function showToast(title, detail) { toast.querySelector('strong').textContent = title; toast.querySelector('small').textContent = detail; toast.classList.add('show'); clearTimeout(timer); timer = setTimeout(() => toast.classList.remove('show'), 3500) }
document.querySelector('[data-alert]').addEventListener('click', () => showToast('Export prepared', 'This is a demonstration prototype.'));
document.querySelector('.application-form').addEventListener('submit', event => { event.preventDefault(); modal.classList.remove('open'); showToast('Application created', 'A new walk-in record was added to the queue.') });
toast.querySelector('button').addEventListener('click', () => toast.classList.remove('show'));
