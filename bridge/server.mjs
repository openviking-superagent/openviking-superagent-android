#!/usr/bin/env node
// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0
//
// A deliberately small read-only gateway for the SuperAgent activity console.
// It exists because the console only accepts loopback Host headers; a phone on
// the LAN cannot satisfy that check directly. The gateway binds to a LAN or
// tailnet address, requires a shared token, and forwards only the two read-only
// JSON endpoints with a loopback Host header.

import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_UPSTREAM_BYTES = 8 * 1024 * 1024;
const ALLOWED_PATHS = new Set(['/api/instances', '/api/activity', '/health', '/ready']);

function usage() {
  return `SuperAgent mobile gateway

Usage:
  node bridge/server.mjs --config bridge/config.local.json
  node bridge/server.mjs --target http://127.0.0.1:3737 --host 0.0.0.0 --port 8787 --token <token>

Options:
  --config <path>  JSON config file; see bridge/config.example.json
  --target <url>   SuperAgent web console (default: http://127.0.0.1:3737)
  --host <addr>    Listen address (default: 127.0.0.1)
  --port <n>       Listen port (default: 8787)
  --token <value>  Shared token required from clients. Also read from
                   SUPERAGENT_BRIDGE_TOKEN. Required when --host is not loopback.
  --token-file <path>
                   Read the token from a file (trailing newline allowed).
  --help           Show this help.

Precedence: CLI > environment > config file > defaults.

The gateway is read-only: GET /api/instances, /api/activity, /health, /ready.
Put it behind Tailscale, WireGuard, or an SSH tunnel on untrusted networks.
`;
}

function readTokenFile(file) {
  const value = fs.readFileSync(file, 'utf8').trim();
  if (!value) throw new Error(`token file is empty: ${file}`);
  return value;
}

function loadConfigFile(file) {
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`cannot read config file ${file}: ${error.message}`);
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`config file must contain a JSON object: ${file}`);
  }
  const allowed = new Set(['target', 'host', 'port', 'token', 'tokenFile']);
  for (const key of Object.keys(parsed)) {
    if (!allowed.has(key)) throw new Error(`unknown config key: ${key}`);
  }
  return parsed;
}

function parseArgs(argv) {
  const options = {
    target: 'http://127.0.0.1:3737',
    host: '127.0.0.1',
    port: 8787,
    token: '',
  };

  let configPath = null;
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--config') {
      configPath = argv[i + 1];
      if (!configPath || configPath.startsWith('--')) throw new Error('--config needs a path');
      i += 1;
    }
  }
  if (configPath) {
    const fileOptions = loadConfigFile(configPath);
    for (const key of ['target', 'host', 'port', 'token']) {
      if (fileOptions[key] !== undefined) options[key] = fileOptions[key];
    }
    if (fileOptions.tokenFile !== undefined) options.token = readTokenFile(fileOptions.tokenFile);
  }

  if (process.env.SUPERAGENT_TARGET) options.target = process.env.SUPERAGENT_TARGET;
  if (process.env.SUPERAGENT_BRIDGE_HOST) options.host = process.env.SUPERAGENT_BRIDGE_HOST;
  if (process.env.SUPERAGENT_BRIDGE_PORT) options.port = Number(process.env.SUPERAGENT_BRIDGE_PORT);
  if (process.env.SUPERAGENT_BRIDGE_TOKEN) options.token = process.env.SUPERAGENT_BRIDGE_TOKEN;

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const take = () => {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith('--')) throw new Error(`${arg} needs a value`);
      i += 1;
      return value;
    };
    if (arg === '--config') {
      take();
      continue;
    }
    if (arg === '--target') options.target = take();
    else if (arg === '--host') options.host = take();
    else if (arg === '--port') options.port = Number(take());
    else if (arg === '--token') options.token = take();
    else if (arg === '--token-file') options.token = readTokenFile(take());
    else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`unknown option: ${arg}`);
  }
  options.port = Number(options.port);
  options.token = String(options.token ?? '');
  return options;
}

function isLoopback(host) {
  return host === '127.0.0.1' || host === '::1' || host === 'localhost';
}

function sameSecret(provided, expected) {
  if (typeof provided !== 'string' || !expected) return false;
  const a = createHash('sha256').update(provided).digest();
  const b = createHash('sha256').update(expected).digest();
  return timingSafeEqual(a, b);
}

function authorized(request, token) {
  if (!token) return true; // only allowed when listening on loopback
  const header = request.headers.authorization;
  const bearer = typeof header === 'string' && /^Bearer\s+/i.test(header)
    ? header.replace(/^Bearer\s+/i, '')
    : '';
  const alternate = request.headers['x-superagent-token'];
  return sameSecret(bearer || alternate, token);
}

function send(response, status, contentType, body) {
  const payload = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': contentType,
    'content-length': payload.length,
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  });
  response.end(payload);
}

function sendJson(response, status, body) {
  send(response, status, 'application/json; charset=utf-8', JSON.stringify(body));
}

function forward(clientRequest, clientResponse, target, token) {
  if (clientRequest.method !== 'GET') {
    sendJson(clientResponse, 405, { error: 'method_not_allowed' });
    return;
  }
  const requestUrl = new URL(clientRequest.url || '/', 'http://gateway.local');
  if (!ALLOWED_PATHS.has(requestUrl.pathname)) {
    sendJson(clientResponse, 404, { error: 'not_found' });
    return;
  }
  if (!authorized(clientRequest, token)) {
    sendJson(clientResponse, 401, { error: 'unauthorized' });
    return;
  }

  const upstreamPath = `${target.pathname.replace(/\/$/, '')}${requestUrl.pathname}${requestUrl.search}`;
  const transport = target.protocol === 'https:' ? https : http;
  const upstream = transport.request({
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port || (target.protocol === 'https:' ? 443 : 80),
    method: 'GET',
    path: upstreamPath,
    headers: {
      // The console verifies the Host header, not the socket address.
      host: target.host,
      accept: 'application/json',
      connection: 'close',
    },
    agent: false,
  }, (upstreamResponse) => {
    const chunks = [];
    let bytes = 0;
    upstreamResponse.on('data', (chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_UPSTREAM_BYTES) {
        upstreamResponse.destroy(new Error('upstream response too large'));
        return;
      }
      chunks.push(chunk);
    });
    upstreamResponse.on('end', () => {
      const body = Buffer.concat(chunks);
      const contentType = upstreamResponse.headers['content-type'] || 'application/json; charset=utf-8';
      send(clientResponse, upstreamResponse.statusCode || 502, contentType, body);
    });
    upstreamResponse.on('error', () => {
      if (!clientResponse.headersSent) sendJson(clientResponse, 502, { error: 'upstream_error' });
      else clientResponse.destroy();
    });
  });

  upstream.setTimeout(15_000, () => upstream.destroy(new Error('upstream timeout')));
  upstream.on('error', (error) => {
    if (!clientResponse.headersSent) {
      sendJson(clientResponse, 502, {
        error: 'upstream_unavailable',
        detail: error.message,
      });
    } else {
      clientResponse.destroy();
    }
  });
  clientRequest.on('aborted', () => upstream.destroy());
  clientResponse.on('close', () => {
    if (!clientResponse.writableEnded) upstream.destroy();
  });
  upstream.end();
}

export function startGateway(options) {
  const target = new URL(options.target);
  if (target.protocol !== 'http:' && target.protocol !== 'https:') {
    throw new Error('--target must be http:// or https://');
  }
  if (!isLoopback(options.host) && !options.token) {
    throw new Error('refusing to listen on a non-loopback address without --token (or SUPERAGENT_BRIDGE_TOKEN)');
  }

  const server = http.createServer((request, response) => {
    try {
      forward(request, response, target, options.token);
    } catch (error) {
      if (!response.headersSent) sendJson(response, 500, { error: 'internal_error' });
      else response.destroy();
    }
  });
  server.keepAliveTimeout = 5_000;
  server.headersTimeout = 6_000;
  server.requestTimeout = 20_000;
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port, options.host, () => {
      server.off('error', reject);
      resolve(server);
    });
  });
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    console.error(usage());
    process.exit(2);
  }
  if (options.help) {
    console.log(usage());
    return;
  }
  if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) {
    console.error('invalid --port');
    process.exit(2);
  }

  let server;
  try {
    server = await startGateway(options);
  } catch (error) {
    console.error(`gateway failed: ${error.message}`);
    process.exit(1);
  }
  const address = server.address();
  const shownHost = options.host === '0.0.0.0' ? '<this-machine-ip>' : options.host;
  console.log(`SuperAgent gateway: http://${shownHost}:${address.port}`);
  console.log(`target: ${options.target}`);
  console.log(options.token ? 'auth: bearer token required' : 'auth: none (loopback only)');
  if (isLoopback(options.host)) {
    console.log('Reachable from this machine only. Use --host 0.0.0.0 plus --token for a phone on the LAN.');
  } else {
    console.log('Only share this address over a trusted network or tunnel.');
  }

  const stop = () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2_000).unref();
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
