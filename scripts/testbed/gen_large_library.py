#!/usr/bin/env python3
"""Generate a large tagged MP3 library for performance testing.

Usage: gen_large_library.py <out-dir> <artists> <albums-per-artist> <tracks-per-album>
       e.g. gen_large_library.py ~/polyphony-testbed/music-large 1000 5 10   # 50,000 tracks, ~1 GB

One 4-second MP3 payload is rendered with ffmpeg once; every track is that payload with its own
ID3v2.3 tags written directly, so 50k files take seconds rather than 50k ffmpeg launches. Artists,
albums, titles, years and genres are drawn from word lists so the alphabet rail, sorts and search
get realistic spread. Each album gets a solid-colour cover.jpg. Requires ffmpeg on PATH.
"""
import os, random, shutil, struct, subprocess, sys
ROOT = sys.argv[1]; N_ARTISTS = int(sys.argv[2]); ALBUMS = int(sys.argv[3]); TRACKS = int(sys.argv[4])
random.seed(9)
os.makedirs(ROOT, exist_ok=True)
tmp = os.path.join(ROOT, "_src"); os.makedirs(tmp, exist_ok=True)
src = os.path.join(tmp, "src.mp3")
if not os.path.exists(src):
    subprocess.run(["ffmpeg","-loglevel","error","-y","-f","lavfi","-i","sine=frequency=440:duration=4",
                    "-ac","1","-ar","22050","-b:a","32k","-id3v2_version","0","-write_id3v1","0","-write_xing","0",src],check=True)
payload = open(src,"rb").read()
if payload[:3]==b"ID3":
    sz = ((payload[6]&0x7f)<<21)|((payload[7]&0x7f)<<14)|((payload[8]&0x7f)<<7)|(payload[9]&0x7f); payload = payload[10+sz:]
covers=[]
for i,c in enumerate(["0x1f6feb","0xb3261e","0x6750a4","0x386a20","0x7d5260","0x006874","0x8a2be2","0xff8c00","0x2f4f4f","0x8b0000","0x4682b4","0x556b2f"]):
    p=os.path.join(tmp,f"cover{i}.jpg")
    if not os.path.exists(p):
        subprocess.run(["ffmpeg","-loglevel","error","-y","-f","lavfi","-i",f"color=c={c}:s=500x500:d=1","-frames:v","1","-q:v","6",p],check=True)
    covers.append(p)
A=["Amber","Blue","Cobalt","Dusty","Ember","Fallow","Golden","Hollow","Iron","Jade","Kestrel","Lunar","Mossy","Northern","Opal","Pale","Quiet","Rust","Silver","Tidal","Umber","Velvet","Wild","Xeric","Yellow","Zinc"]
B=["Pines","Harbour","Lanterns","Orchard","Signal","Meadow","Static","Compass","Fathom","Glass","Rivers","Summit","Parade","Tundra","Vessel","Window","Archive","Beacon","Canyon","Drift"]
T=["Overture","Kettle Song","Homeward","Timberline","Low Tide","Paper Moon","Northbound","Afterglow","Second Light","Weathervane","Small Hours","Ferry","Coastline","Half Light","Pilgrim","Clockwork","Blue Door","Orchard Road","Late Frost","Harvest","Lighthouse","Quarry","Evening Train","Wintering"]
G=["Rock","Folk","Electronic","Jazz","Ambient","Indie","Classical","Hip-Hop","Soul","Metal","Pop","Country"]
def frame(fid, text):
    data = b"\x00" + text.encode("latin-1","replace") + b"\x00"
    return fid.encode() + struct.pack(">I", len(data)) + b"\x00\x00" + data
def tag(frames):
    body=b"".join(frames); n=len(body)
    return b"ID3\x03\x00\x00" + bytes([(n>>21)&0x7f,(n>>14)&0x7f,(n>>7)&0x7f,n&0x7f]) + body
count=0
for ai in range(N_ARTISTS):
    artist = f"{A[ai%len(A)]} {B[(ai//len(A))%len(B)]}" + (f" {ai//(len(A)*len(B))+1}" if ai>=len(A)*len(B) else "")
    adir = os.path.join(ROOT, artist.replace("/","-"))
    for k in range(ALBUMS):
        album = f"{B[(ai*7+k)%len(B)]} {T[(ai*3+k*5)%len(T)]}" + ("" if k==0 else f" Vol. {k+1}")
        year = 1970 + (ai*13 + k*7) % 55; genre = G[(ai+k)%len(G)]
        d = os.path.join(adir, album.replace("/","-")); os.makedirs(d, exist_ok=True)
        shutil.copyfile(covers[(ai+k)%len(covers)], os.path.join(d,"cover.jpg"))
        for t in range(1, TRACKS+1):
            title = f"{T[(ai+k*3+t*5)%len(T)]}" + (f" (Part {t})" if t>len(T) else "")
            frames=[frame("TIT2",title),frame("TPE1",artist),frame("TPE2",artist),frame("TALB",album),
                    frame("TRCK",f"{t}/{TRACKS}"),frame("TYER",str(year)),frame("TCON",genre)]
            with open(os.path.join(d, f"{t:02d} - {title.replace('/','-')}.mp3"),"wb") as f:
                f.write(tag(frames)); f.write(payload)
            count+=1
print("tracks written:", count)
