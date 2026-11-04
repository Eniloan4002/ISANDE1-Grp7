// Shared client-side pagination for the long record tables.
//
// The screens each filter their own rows; this only decides which of the rows
// that SURVIVED the filter are on screen. Call render() with the matched rows
// in display order and it hides the rest of that page, redraws the page
// buttons, and hands back the numbers for the "Showing x of y" label.
//
// Rows the caller has already hidden are left alone, so filtering and paging
// compose instead of fighting each other.
(function () {
  const DEFAULT_PAGE_SIZE = 25;
  const WINDOW = 2; // page numbers shown either side of the current page

  function attach(options) {
    const controls = typeof options.controls === 'string'
      ? document.querySelector(options.controls)
      : options.controls;
    const pageSize = options.pageSize || DEFAULT_PAGE_SIZE;
    let page = 1;
    let pageCount = 1;
    let onChange = options.onChange || function () {};

    // Page numbers to draw: always first and last, plus a window around the
    // current page, with null standing in for a gap.
    function pageList() {
      if (pageCount <= 7) return Array.from({ length: pageCount }, (unused, i) => i + 1);
      const wanted = new Set([1, pageCount]);
      for (let p = page - WINDOW; p <= page + WINDOW; p += 1) {
        if (p >= 1 && p <= pageCount) wanted.add(p);
      }
      const sorted = [...wanted].sort((a, b) => a - b);
      const withGaps = [];
      sorted.forEach((value, idx) => {
        if (idx && value - sorted[idx - 1] > 1) withGaps.push(null);
        withGaps.push(value);
      });
      return withGaps;
    }

    function drawControls() {
      if (!controls) return;
      const parts = [`<button type="button" data-page="prev" aria-label="Previous page"${page === 1 ? ' disabled' : ''}>&lt;</button>`];
      pageList().forEach(value => {
        if (value === null) {
          parts.push('<button type="button" disabled aria-hidden="true">...</button>');
          return;
        }
        parts.push(`<button type="button" data-page="${value}"${value === page ? ' class="current" aria-current="page"' : ''}>${value}</button>`);
      });
      parts.push(`<button type="button" data-page="next" aria-label="Next page"${page === pageCount ? ' disabled' : ''}>&gt;</button>`);
      controls.innerHTML = parts.join('');
      // A single page of results has nothing to navigate.
      controls.hidden = pageCount <= 1;
    }

    if (controls) {
      controls.addEventListener('click', event => {
        const button = event.target.closest('button[data-page]');
        if (!button || button.disabled) return;
        const value = button.dataset.page;
        const next = value === 'prev' ? page - 1 : value === 'next' ? page + 1 : Number(value);
        if (next === page || next < 1 || next > pageCount) return;
        page = next;
        onChange();
      });
    }

    return {
      // rows = the rows that matched the current filter, in display order.
      render(rows) {
        pageCount = Math.max(1, Math.ceil(rows.length / pageSize));
        if (page > pageCount) page = pageCount;
        const start = (page - 1) * pageSize;
        const end = start + pageSize;
        rows.forEach((row, index) => { row.hidden = index < start || index >= end; });
        drawControls();
        return {
          page,
          pageCount,
          total: rows.length,
          from: rows.length ? start + 1 : 0,
          to: Math.min(end, rows.length)
        };
      },
      // Call when the filter changes so the user is not stranded on a page that
      // no longer has any matching rows.
      reset() { page = 1; },
      setOnChange(fn) { onChange = fn; }
    };
  }

  window.Pagination = { attach, DEFAULT_PAGE_SIZE };
})();
