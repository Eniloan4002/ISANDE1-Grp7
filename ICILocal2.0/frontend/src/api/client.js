import axios from 'axios';

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api' });

let accessToken = null;
let onUnauthorized = null;

export function configureApiAuth(token, unauthorizedHandler) {
  accessToken = token;
  onUnauthorized = unauthorizedHandler;
}

api.interceptors.request.use(config => {
  if (accessToken) config.headers.set('Authorization', `Bearer ${accessToken}`);
  return config;
});

api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) onUnauthorized?.();
  return Promise.reject(error);
});
