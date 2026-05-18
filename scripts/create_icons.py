import struct, zlib, os

def create_png(width, height, color):
    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    header = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    
    raw = b''
    for y in range(height):
        raw += b'\x00'
        for x in range(width):
            cx, cy = width//2, height//2
            r = min(width, height)//2 - 2
            dist = ((x-cx)**2 + (y-cy)**2) ** 0.5
            if dist <= r:
                raw += bytes(color)
            else:
                raw += b'\xe8\xe8\xe8'
    
    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    return header + ihdr + idat + iend

density_map = {
    48: 'mdpi', 72: 'hdpi', 96: 'xhdpi',
    144: 'xxhdpi', 192: 'xxxhdpi'
}

base = '/opt/data/home/android-build/diarizer-sherpa/app/src/main/res'

# Launcher icon - blue
for size, density in density_map.items():
    d = os.path.join(base, f'mipmap-{density}')
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, 'ic_launcher.png'), 'wb') as f:
        f.write(create_png(size, size, (66, 133, 244)))

# Round icon - amber
for size, density in density_map.items():
    d = os.path.join(base, f'mipmap-{density}')
    with open(os.path.join(d, 'ic_launcher_round.png'), 'wb') as f:
        f.write(create_png(size, size, (244, 180, 66)))

print('All icons created')
