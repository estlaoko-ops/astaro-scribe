import struct, zlib, os, math

def create_png(width, height):
    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    header = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))  # 8-bit RGBA

    cx, cy = width//2, height//2
    r = min(width, height)//2 - 2
    inner_r = int(r * 0.55)  # where the A crossbar sits
    letter_size = int(r * 0.7)  # letter height

    raw = b''
    for y in range(height):
        raw += b'\x00'  # filter byte
        for x in range(width):
            dist = math.sqrt((x-cx)**2 + (y-cy)**2)
            if dist <= r:
                # Inside circle base - blue
                base_r, base_g, base_b = (66, 133, 244)

                # Check if we're drawing the golden "A"
                # Normalize coordinates so A is centered
                lx = x - cx
                ly = y - cy

                # "A" shape: two diagonal lines and a horizontal crossbar
                # Using letter coordinates roughly
                half_w = letter_size // 2
                a_top_y = -int(letter_size * 0.45)
                a_bottom_y = int(letter_size * 0.5)
                crossbar_y = int(letter_size * 0.1)

                # Left leg: from ( -half_w, bottom) to (0, top)
                # Right leg: from (half_w, bottom) to (0, top)
                # Crossbar: horizontal at crossbar_y

                def point_to_line_dist(px, py, x1, y1, x2, y2):
                    dx = x2 - x1
                    dy = y2 - y1
                    if dx == 0 and dy == 0:
                        return math.sqrt((px-x1)**2 + (py-y1)**2)
                    t = ((px - x1) * dx + (py - y1) * dy) / (dx*dx + dy*dy)
                    t = max(0, min(1, t))
                    nx = x1 + t * dx
                    ny = y1 + t * dy
                    return math.sqrt((px - nx)**2 + (py - ny)**2)

                stroke = max(2, int(r * 0.14))
                cross_stroke = max(2, int(r * 0.10))

                is_a = False
                # Left leg
                d1 = point_to_line_dist(lx, ly, -half_w, a_bottom_y, 0, a_top_y)
                if d1 <= stroke:
                    is_a = True
                # Right leg
                d2 = point_to_line_dist(lx, ly, half_w, a_bottom_y, 0, a_top_y)
                if d2 <= stroke:
                    is_a = True
                # Crossbar
                if abs(ly - crossbar_y) <= cross_stroke and abs(lx) <= half_w * 0.7:
                    is_a = True

                if is_a:
                    # Golden A
                    golden = (255, 215, 0)  # pure gold
                    r, g, b, a = golden[0], golden[1], golden[2], 255
                else:
                    # Anti-aliased edge
                    if dist > r - 1:
                        alpha = int(max(0, min(255, (r - dist + 1) * 255)))
                    else:
                        alpha = 255
                    r, g, b, a = base_r, base_g, base_b, alpha

                raw += struct.pack('BBBB', r, g, b, a)
            else:
                raw += b'\x00\x00\x00\x00'  # transparent

    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    return header + ihdr + idat + iend

density_map = {
    48: 'mdpi', 72: 'hdpi', 96: 'xhdpi',
    144: 'xxhdpi', 192: 'xxxhdpi'
}

base = '/opt/data/home/android-build/diarizer-sherpa/app/src/main/res'

# Round icon - same A but amber/golden background
for size, density in density_map.items():
    d = os.path.join(base, f'mipmap-{density}')
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, 'ic_launcher.png'), 'wb') as f:
        f.write(create_png(size, size))

# Round icon - also the same design
for size, density in density_map.items():
    d = os.path.join(base, f'mipmap-{density}')
    with open(os.path.join(d, 'ic_launcher_round.png'), 'wb') as f:
        f.write(create_png(size, size))

print('All Astaro icons created with golden A')
