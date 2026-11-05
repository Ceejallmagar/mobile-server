import hashlib, lzma, os, re, shutil, subprocess, tempfile, urllib.request, zipfile
from pathlib import Path

BASE='https://packages.termux.dev/apt/termux-main/'
PACKAGE_NAMES=['mariadb','libandroid-support','libbz2','libc++','libcrypt','libedit','liblz4','libxml2','liblzma','ncurses','openssl','pcre2','zlib','zstd','libandroid-glob','libiconv']
PINS={
'mariadb':('2:13.0.2','df1249a2de5faf1878a17b4b7188362891cb81ce0267d6c3150891a013975062'),
'libandroid-support':('29-1','f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517'),
'libbz2':('1.0.8-8','4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba'),
'libc++':('30','53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca'),
'libcrypt':('0.2-6','6c283eed576b98cc3568f99638156f8588f77d979579d03bef8683d6eb8601e1'),
'libedit':('20260512-3.1-0','728da8f45e9c0027f0c5844346dd8cea153a43394bceee36a257972acf0ed440'),
'liblz4':('1.11.0','3befcdf8c984a2a6c5d60e08544b14b404d8479ca6b1fda84412356985c16907'),
'libxml2':('2.15.4-1','d315861bbcf716cfb088f53cddab9fb6a96430cc66769f79774cc69c2f53bcff'),
'liblzma':('5.8.4','44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0'),
'ncurses':('6.6.20260307+really6.5.20250830','f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99'),
'openssl':('1:3.6.5','a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b'),
'pcre2':('10.49','c27995e5f52b8ecc9b8b3e662eb08e49f82a18bfd3f34343936acc7b23964724'),
'zlib':('1.3.2','75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9'),
'zstd':('1.5.7-1','e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3'),
'libandroid-glob':('0.6-3','2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826'),
'libiconv':('1.19','fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37')}
index=urllib.request.urlopen(BASE+'dists/stable/main/binary-aarch64/Packages').read().decode()
def fields_for(name):
 for block in index.split('\n\n'):
  if block.startswith('Package: '+name+'\n') and 'Architecture: aarch64' in block:
   return {k:v for line in block.splitlines() if ': ' in line for k,v in [line.split(': ',1)]}
 raise ValueError('Package unavailable: '+name)

tmp=Path('/tmp/mdb-bundle-work'); shutil.rmtree(tmp,ignore_errors=True); tmp.mkdir()
extract=tmp/'all'; extract.mkdir()
for name in PACKAGE_NAMES:
 f=fields_for(name); deb=Path('/tmp/mariadb-packages')/(name+'.deb')
 expected_version, expected_sha256 = PINS[name]
 if f['Version'] != expected_version or f['SHA256'] != expected_sha256:
  raise RuntimeError(f"Pinned Termux package changed for {name}: expected {expected_version}/{expected_sha256}")
 if not deb.exists() or hashlib.sha256(deb.read_bytes()).hexdigest()!=expected_sha256:
  deb.parent.mkdir(parents=True,exist_ok=True)
  urllib.request.urlretrieve(BASE+f['Filename'],deb)
  assert hashlib.sha256(deb.read_bytes()).hexdigest()==expected_sha256, name+' checksum mismatch'
 subprocess.run(['dpkg-deb','-x',str(deb),str(extract)],check=True)

prefix=extract/'data/data/com.termux/files/usr'
out=tmp/'payload'; (out/'usr/bin').mkdir(parents=True); (out/'usr/lib').mkdir(parents=True); (out/'usr/share').mkdir(parents=True)
bins=['mariadbd','mariadb-install-db','mysql_install_db','my_print_defaults','mariadb','mysql','mariadb-admin','mysqladmin','mariadbd-safe-helper','mariadb-waitpid']
for b in bins:
 p=prefix/'bin'/b
 if p.exists(): shutil.copy2(p,out/'usr/bin'/b)
# Server files: all compiled runtime plugins and message/charset/system scripts.
for rel in ['lib/mysql/plugin','share/mariadb']:
 src=prefix/rel
 if src.exists(): shutil.copytree(src,out/('usr/'+rel),symlinks=True)
# Runtime dynamic libraries from dependencies plus MariaDB package. Dereference symlinks so the runtime is self-contained.
libdirs=[prefix/'lib',prefix/'lib/aarch64-linux-android']
for src in libdirs:
 if not src.exists(): continue
 for p in src.iterdir():
  if p.name.endswith('.so') or '.so.' in p.name:
   dest=out/'usr/lib'/p.name
   real=p.resolve()
   if real.is_file() and not dest.exists(): shutil.copy2(real,dest)
# MariaDB server helper refers to Termux's fixed prefix. Replace it when the app installs the runtime.
# Include the source prefix token; Android service rewrites it to filesDir on extraction.
# Patch ELF runpaths to a relocatable path.
for p in list((out/'usr/bin').iterdir())+list((out/'usr/lib').glob('*.so*'))+list((out/'usr/lib/mysql/plugin').glob('*.so')):
 try:
  if p.read_bytes()[:4]==b'\x7fELF':
   subprocess.run(['patchelf','--set-rpath','$ORIGIN/../lib',str(p)],check=True,stdout=subprocess.DEVNULL)
 except Exception as e: print('patch warning',p,e)
# Zip with maximum compression; preserve symlinks by resolving them.
asset=Path(__file__).resolve().parents[1]/'app/src/main/assets/runtime/mariadb-arm64.zip'
asset.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(asset,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in out.rglob('*'):
  if p.is_file(): z.write(p,p.relative_to(out).as_posix())
print('archive',asset,asset.stat().st_size,'bytes')
