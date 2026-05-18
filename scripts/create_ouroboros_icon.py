import struct, zlib, os, math

def create_ouroboros_png(width, height):
    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    header = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))  # 8-bit RGBA
    
    cx, cy = width // 2, height // 2
    r = min(width, height) // 2 - 4
    thickness = r // 4
    
    raw = b''
    for y in range(height):
        raw += b'\x00'  # filter byte (none)
        for x in range(width):
            dx, dy = x - cx, y - cy
            dist = math.sqrt(dx*dx + dy*dy)
            angle = math.atan2(dy, dx)
            
            # Ouroboros: a circular snake with a gap at the top
            # Body is a ring
            if r - thickness <= dist <= r + thickness:
                # Check if we're at the "head" area (left side of gap)
                head_angle_range = 0.3  # radians
                gap_angle = -math.pi / 2  # gap at the top
                
                if abs(angle - gap_angle) < head_angle_range:
                    # Head - make it slightly thicker and add eye
                    if dist >= r - thickness * 0.3 and dist <= r + thickness * 0.3:
                        # Orange-gold color
                        raw += bytes([255, 140, 50, 255])
                    else:
                        raw += bytes([50, 50, 60, 255])
                elif abs(angle - gap_angle) < 0.5:
                    # Gap - transparent
                    raw += bytes([0, 0, 0, 0])
                else:
                    # Body - dark teal/emerald
                    shade = int(80 + 60 * math.sin(angle * 3))
                    raw += bytes([shade, shade + 40, shade - 20, 255])
            elif dist < r - thickness and r - thickness - 4 <= dist <= r - thickness:
                # Inner edge glow
                raw += bytes([50, 120, 100, 100])
            elif dist < r - thickness - 4:
                # Inside the ring - transparent
                raw += bytes([0, 0, 0, 0])
            else:
                # Outside - transparent
                raw += bytes([0, 0, 0, 0])
    
    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    return header + ihdr + idat + iend

density_map = {
    48: 'mdpi', 72: 'hdpi', 96: 'xhdpi',
    144: 'xxhdpi', 192: 'xxxhdpi'
}

base = '/opt/data/home/android-build/diarizer-sherpa/app/src/main/res'

for name in ['ic_launcher', 'ic_launcher_round']:
    for size, density in density_map.items():
        d = os.path.join(base, f'mipmap-{density}')
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, f'{name}.png'), 'wb') as f:
            f.write(create_ouroboros_png(size, size))

print('Ouroboros icons created!')
