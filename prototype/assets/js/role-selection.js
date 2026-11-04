// Prototype role switcher: no credentials or authentication are involved.
document.querySelector('#role-form').addEventListener('submit', event => {
  event.preventDefault();
  const destination = document.querySelector('#role-select').value;
  if (destination) window.location.href = destination;
});
