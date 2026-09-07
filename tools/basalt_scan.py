"""Read-only Anvil/NBT survey of the supplied 26.2 Nether farm (stdlib only)."""
import collections, gzip, io, json, math, pathlib, struct, sys, zlib


class NBT:
    def __init__(self, data): self.f = io.BytesIO(data)
    def number(self, fmt): return struct.unpack('>' + fmt, self.f.read(struct.calcsize(fmt)))[0]
    def string(self): return self.f.read(self.number('H')).decode('utf-8', errors='replace')
    def tag(self, kind):
        if kind in range(1, 7): return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind == 8: return self.string()
        if kind == 9:
            sub, n = self.number('B'), self.number('i')
            return [self.tag(sub) for _ in range(n)]
        if kind == 10:
            result = {}
            while (sub := self.number('B')):
                name = self.string(); result[name] = self.tag(sub)
            return result
        if kind in (7, 11, 12):
            n = self.number('i'); fmt = {7:'b',11:'i',12:'q'}[kind]
            return list(struct.unpack('>' + str(n) + fmt, self.f.read(n * struct.calcsize(fmt))))
        raise ValueError(kind)
    def root(self):
        kind = self.number('B'); self.string(); return self.tag(kind)


def chunks(path):
    data = path.read_bytes()
    for i in range(1024):
        offset = int.from_bytes(data[i*4:i*4+3], 'big') * 4096
        if not offset: continue
        size = int.from_bytes(data[offset:offset+4], 'big')
        compression = data[offset+4]
        raw = data[offset+5:offset+4+size]
        raw = {1:gzip.decompress, 2:zlib.decompress, 3:lambda x:x}[compression](raw)
        yield NBT(raw).root()


def blocks(chunk):
    for sec in chunk.get('sections', []):
        states = sec.get('block_states', {})
        palette = states.get('palette', [])
        if not palette: continue
        bits = max(4, (len(palette)-1).bit_length()); per = 64 // bits
        packed = states.get('data', [])
        for i in range(4096):
            index = ((packed[i//per] & ((1<<64)-1)) >> ((i%per)*bits)) & ((1<<bits)-1) if packed else 0
            state = palette[index]
            yield (chunk['xPos']*16+i%16, sec['Y']*16+i//256, chunk['zPos']*16+(i//16)%16), state


if __name__ == '__main__':
    assert NBT(b'\x0a\x00\x00\x03\x00\x01x\x00\x00\x00\x2a\x00').root() == {'x':42}
    world, out = map(pathlib.Path, sys.argv[1:3]); out.mkdir(parents=True, exist_ok=True)
    import re
    source = pathlib.Path('src/main/java/com/damia/movrand/BlockTargets.java').read_text()
    redstone = set(re.findall(r'"([a-z_]+)"', source.split('REDSTONE_IDS = Set.of(')[1].split(');')[0]))
    def selected(name): return name in redstone or name.endswith(('_button','_pressure_plate'))
    found, counts, present = [], collections.Counter(), []
    cells = {}
    region = world/'dimensions/minecraft/the_nether/region/r.2.0.mca'
    for chunk in chunks(region):
        if not (66 <= chunk['xPos'] <= 74 and 3 <= chunk['zPos'] <= 12): continue
        present.append([chunk['xPos'],chunk['zPos']])
        for pos, state in blocks(chunk):
            x,y,z = pos
            if not (1060 <= x <= 1195 and 35 <= y <= 100 and 55 <= z <= 195): continue
            name = state['Name'].removeprefix('minecraft:')
            counts[name] += 1
            if name not in ('air','cave_air','void_air'): cells[','.join(map(str,pos))] = state
            if selected(name): found.append(dict(x=x,y=y,z=z,block=name,properties=state.get('Properties',{})))
    (out/'survey.json').write_text(json.dumps(dict(bounds=[1060,35,55,1195,100,195],counts=counts,redstone=found,present_chunks=present),indent=2))
    (out/'cells.json').write_text(json.dumps(cells))
    print(json.dumps(dict(counts=counts,redstone_count=len(found),redstone_bounds=[[min(b[k] for b in found),max(b[k] for b in found)] for k in ('x','y','z')],redstone_types=collections.Counter(b['block'] for b in found),chunks=len(present)),indent=2))
