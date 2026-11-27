// 24x24 stroke icons for the side panel. Keys match `iconByPath` in routes/routeConfig.js.
const PATHS = {
  queue: 'M4 6h16M4 12h16M4 18h10',
  intake: 'M15 19v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19 8v6M16 11h6',
  dupes: 'M9 9h10v10H9zM5 15V5h10',
  reqs: 'M9 12l2 2 4-4M4 5h16v14H4z',
  attend: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  place: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  sections: 'M3 5h18M3 12h18M3 19h18',
  people: 'M16 20v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6M22 20v-1a4 4 0 0 0-3-3.9M16 5.1a3 3 0 0 1 0 5.8',
  history: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  alert: 'M12 8v5M12 16h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  shield: 'M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6zM9 12l2 2 4-4',
  lock: 'M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3',
  chart: 'M5 20V10M12 20V4M19 20v-7',
  edit: 'M4 20h4L19 9l-4-4L4 16zM13 7l4 4',
  book: 'M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11',
  settings: 'M4 7h10M18 7h2M4 17h2M10 17h10M16 4v6M8 14v6',
  menu: 'M4 7h16M4 12h16M4 17h16',
  dot: 'M12 12h.01',
};

export function Icon({ name, size = 18 }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d={PATHS[name] ?? PATHS.dot} /></svg>;
}
