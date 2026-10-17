#!/usr/bin/env python3
"""Run yt-dlp through a DNS-pinned public-only proxy, including redirects/media URLs.
No credentials, cookies, local URLs, external downloaders or inherited proxy settings.

Spotify streams are DRM-protected, so a Spotify track link is resolved to artist, title and
duration from its public embed page, and the same song is searched on YouTube with a duration
window. Player-facing failures are written to error.txt; a suggested disc name to title.txt.
"""
import http.server, ipaddress, json, os, re, resource, select, socket, subprocess, sys, threading
import urllib.parse, urllib.request

SPOTIFY_HOSTS = ('open.spotify.com', 'play.spotify.com', 'spotify.link', 'spotify.app.link')
SPOTIFY_REDIRECT_HOSTS = SPOTIFY_HOSTS + ('www.spotify.com',)
USER_AGENT = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'


class Refusal(Exception):
    """A failure with a message for the player."""


def public_address(host, port):
    if port not in (80, 443):
        raise ValueError('port')
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not addresses:
        raise ValueError('dns')
    for family, kind, proto, _, address in addresses:
        ip = ipaddress.ip_address(address[0])
        if not ip.is_global or ip.is_multicast or (ip.version == 6 and (ip.ipv4_mapped or ip.sixtofour or ip.teredo)):
            raise ValueError('private address')
    family, kind, proto, _, address = addresses[0]
    sock = socket.socket(family, kind, proto)
    sock.settimeout(20)
    sock.connect(address)  # No second DNS lookup: prevents DNS rebinding.
    return sock


class Proxy(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def relay(self, upstream):
        try:
            while True:
                ready, _, _ = select.select([self.connection, upstream], [], [], 30)
                if not ready: return
                for src in ready:
                    data = src.recv(65536)
                    if not data: return
                    (upstream if src is self.connection else self.connection).sendall(data)
        finally:
            upstream.close()
    def do_CONNECT(self):
        try:
            u = urllib.parse.urlsplit('https://' + self.path)
            if u.username or u.password or u.path or u.query or u.fragment: raise ValueError()
            upstream = public_address(u.hostname, u.port or 443)
        except Exception:
            self.send_error(403); return
        self.send_response(200); self.end_headers()
        self.relay(upstream)
    def do_GET(self):
        try:
            u = urllib.parse.urlsplit(self.path)
            if u.scheme != 'http' or u.username or u.password: raise ValueError()
            upstream = public_address(u.hostname, u.port or 80)
        except Exception:
            self.send_error(403); return
        path = urllib.parse.urlunsplit(('', '', u.path or '/', u.query, ''))
        headers = ''.join(f'{k}: {v}\r\n' for k,v in self.headers.items()
                          if k.lower() not in ('proxy-connection','proxy-authorization','connection'))
        upstream.sendall((f'GET {path} HTTP/1.1\r\n{headers}Connection: close\r\n\r\n').encode('latin1'))
        self.relay(upstream)


class SpotifyOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u = urllib.parse.urlsplit(newurl)
        if u.scheme != 'https' or u.hostname not in SPOTIFY_REDIRECT_HOSTS:
            raise Refusal('Esse link do Spotify não abriu uma música. Copie o link pelo botão Compartilhar da música.')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(opener, url, limit=2 * 1024 * 1024):
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Accept-Language': 'pt-BR,pt;q=0.9,en;q=0.8'})
    with opener.open(request, timeout=20) as response:
        return response.geturl(), response.read(limit).decode('utf-8', 'replace')


def spotify_track(url):
    """Artist, title and duration (seconds) of a Spotify track link."""
    opener = urllib.request.build_opener(SpotifyOnly)
    path = urllib.parse.urlsplit(url).path
    if urllib.parse.urlsplit(url).hostname in ('spotify.link', 'spotify.app.link'):
        # Short links answer with an HTTP redirect or, for some clients, a page that embeds the target.
        final, page = fetch(opener, url, 262144)
        found = re.search(r'open\.spotify\.com(/(?:intl-[a-z-]+/)?(?:track|album|playlist|episode|show|artist)/[A-Za-z0-9]{22})', page)
        path = urllib.parse.urlsplit(final).path if 'open.spotify.com' in final or not found else found.group(1)
    match = re.search(r'/(track|album|playlist|episode|show|artist)/([A-Za-z0-9]{22})', path)
    if not match:
        raise Refusal('Não reconheci esse link do Spotify. Use o link de uma música (Compartilhar > Copiar link).')
    if match.group(1) != 'track':
        raise Refusal('Esse link do Spotify é de álbum, playlist ou podcast. Mande o link de uma música só.')
    _, page = fetch(opener, 'https://open.spotify.com/embed/track/' + match.group(2))
    data = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', page, re.S)
    try:
        entity = json.loads(data.group(1))['props']['pageProps']['state']['data']['entity']
        title = entity.get('name') or entity['title']
        artists = ', '.join(a['name'] for a in entity.get('artists') or [] if a.get('name'))
        seconds = round(int(entity['duration']) / 1000)
    except Exception:
        raise Refusal('Não consegui ler essa música do Spotify agora. Tente o link do YouTube ou envie o arquivo.')
    return artists, title, seconds


def ytdlp_args(binary, deno, ffmpeg, port, maxbytes, out):
    return [binary, '--ignore-config', '--no-progress', '--no-cache-dir', '--no-warnings',
            '--socket-timeout', '20', '--retries', '1', '--fragment-retries', '1',
            '--concurrent-fragments', '1', '--downloader', 'native',
            '--proxy', f'http://127.0.0.1:{port}', '--js-runtimes', f'deno:{deno}',
            '--remote-components', 'ejs:github', '--ffmpeg-location', ffmpeg,
            '--max-filesize', maxbytes, '-f', 'bestaudio/best', '--write-info-json',
            '--no-write-playlist-metafiles', '-o', out + '/source.%(ext)s']


def run(args, env):
    """yt-dlp exit 101 means --max-downloads was reached, i.e. a search found its match."""
    result = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
    sys.stdout.write(result.stdout)
    return (0 if result.returncode == 101 else result.returncode), result.stdout


def downloaded(out):
    return any(name.startswith('source.') and not name.endswith(('.json', '.part', '.ytdl'))
               for name in os.listdir(out))


def main():
    binary, deno, ffmpeg, url, out, maxbytes, maxseconds = sys.argv[1:]
    maxseconds = int(maxseconds)
    u = urllib.parse.urlsplit(url)
    if u.scheme not in ('http','https') or not u.hostname or u.username or u.password:
        sys.exit('Use um link HTTP(S) público.')
    # The standalone yt-dlp binary unpacks shared libraries before running.
    # A 32 MB RLIMIT would kill its bootstrap; the media limit is enforced separately.
    hard_limit = max(128 * 1024 * 1024, int(maxbytes))
    resource.setrlimit(resource.RLIMIT_FSIZE, (hard_limit, hard_limit))
    proxy = http.server.ThreadingHTTPServer(('127.0.0.1',0), Proxy)
    proxy.daemon_threads = True
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    env = {k:v for k,v in os.environ.items() if k.lower() not in ('http_proxy','https_proxy','all_proxy','no_proxy')}
    base = ytdlp_args(binary, deno, ffmpeg, proxy.server_port, maxbytes, out)
    try:
        if u.hostname.lower() in SPOTIFY_HOSTS:
            artists, title, seconds = spotify_track(url)
            if seconds > maxseconds:
                raise Refusal(f'Essa música tem {seconds // 60}:{seconds % 60:02d}. O limite é {maxseconds // 60}:{maxseconds % 60:02d}.')
            name = f'{artists} - {title}' if artists else title
            with open(os.path.join(out, 'title.txt'), 'w', encoding='utf-8') as f:
                f.write(name)
            # Closest duration first: the official audio upload, then a looser pass for re-uploads.
            for window, query in ((8, name), (25, name + ' audio')):
                low, high = max(1, seconds - window), min(maxseconds, seconds + window)
                code, _ = run(base + ['--max-downloads', '1', '--match-filters', f'duration >= {low} & duration <= {high}',
                                      '--', 'ytsearch8:' + query], env)
                if downloaded(out):
                    sys.exit(0)
            raise Refusal('Não achei essa música no YouTube. Mande o link do YouTube ou envie o arquivo com /disco enviar.')
        code, log = run(base + ['--no-playlist', '--playlist-items', '1',
                                '--match-filters', f'duration <= {maxseconds}', '--', url], env)
        if not downloaded(out):
            if 'does not pass filter' in log:
                raise Refusal(f'Esse áudio passa de {maxseconds // 60} minutos. Escolha um trecho menor ou envie o arquivo.')
            if 'File is larger than max-filesize' in log:
                raise Refusal('Esse áudio é grande demais para baixar. Envie um arquivo menor com /disco enviar.')
            if re.search(r'live stream|is live|Premieres in', log):
                raise Refusal('Transmissões ao vivo não viram disco. Use o link de um vídeo normal.')
            if re.search(r'Private video|Sign in to confirm|age-restricted|confirm your age|members-only', log):
                raise Refusal('Esse vídeo é privado ou pede login. Use outro link ou envie o arquivo.')
            sys.exit(code or 1)
        sys.exit(code)
    except Refusal as refusal:
        with open(os.path.join(out, 'error.txt'), 'w', encoding='utf-8') as f:
            f.write(str(refusal))
        sys.exit(3)
    finally:
        proxy.shutdown()

if __name__ == '__main__': main()
