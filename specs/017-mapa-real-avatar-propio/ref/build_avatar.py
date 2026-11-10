"""Retoque de la hoja de sprites de Miguel -> frames 32x56 + datos para AvatarSprites.java.
Fuente: workspace/user/media_library/image/c9/c9f84e29acffa31ee26b462dbe864f39a539dbacb1d90f688fbf716b43ca1882.jpg
Grid verificado por proyeccion: 4 filas x 9 columnas (celdas en /tmp/cells.json).
Mapeo (documentado en specs/017-mapa-real-avatar-propio/spec.md):
  BOY_FRONT_A=R1C1  BOY_FRONT_B=R1C2   BOY_BACK_A=R1C3  BOY_BACK_B=R1C4
  BOY_SIDE_A=R2C1   BOY_SIDE_B=R3C1
  GIRL_FRONT_A=R4C2 GIRL_FRONT_B=R4C5 GIRL_BACK_A=R4C3 GIRL_BACK_B=R4C4
  GIRL_SIDE_A=R2C1+recolor(pelo rosa)  GIRL_SIDE_B=R3C1+recolor(pelo rosa)
Retoques: fondo JPG -> transparente (flood-fill desde el borde); RGB bajo
transparente rellenado por vecino mas cercano (sin halo blanco al reducir);
escala uniforme (la figura mas alta -> 56px, pies alineados abajo); paleta
global k-means SOLO sobre pixeles de figura (sin blanco); recolor lado chica
en RGB antes de cuantizar (castano -> rosa con el mismo sombreado).
"""
import json
from PIL import Image, ImageDraw, ImageFont
import numpy as np
from scipy import ndimage

SRC='/home/hatch/workspace/user/media_library/image/c9/c9f84e29acffa31ee26b462dbe864f39a539dbacb1d90f688fbf716b43ca1882.jpg'
OUT='/tmp/avatar_out'
FW, FH = 32, 56
NCOLORS = 20
ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._"
assert len(ALPHABET) == 64
rng = np.random.default_rng(0)

im = Image.open(SRC).convert('RGB')
a = np.asarray(im).astype(int)
H, W = a.shape[:2]
near_white = (a.min(axis=2) > 225) & ((a.max(axis=2) - a.min(axis=2)) < 30)
lab, n = ndimage.label(near_white)
border_labels = set(np.unique(np.concatenate([lab[0,:], lab[-1,:], lab[:,0], lab[:,-1]]))) - {0}
background = np.isin(lab, list(border_labels))
figure = ~background
rgba = np.dstack([np.asarray(im), np.where(figure, 255, 0).astype(np.uint8)])
Image.fromarray(rgba, 'RGBA').save(f'{OUT}/clean_sheet.png')

cells = json.load(open('/tmp/cells.json'))
FRAMES = {
 'BOY_FRONT_A':'R1C1','BOY_FRONT_B':'R1C2','BOY_BACK_A':'R1C3','BOY_BACK_B':'R1C4',
 'BOY_SIDE_A':'R2C1','BOY_SIDE_B':'R3C1',
 'GIRL_FRONT_A':'R4C2','GIRL_FRONT_B':'R4C5','GIRL_BACK_A':'R4C3','GIRL_BACK_B':'R4C4',
 'GIRL_SIDE_A':'R2C1','GIRL_SIDE_B':'R3C1',
}
RECOLORED = {'GIRL_SIDE_A','GIRL_SIDE_B'}
SCALE = FH / 283.0

def is_brown_rgb(rgb):
    r,g,b = rgb[:,:,0], rgb[:,:,1], rgb[:,:,2]
    return ((r>=70)&(r<=190)&(g>=40)&(g<=140)&(b>=20)&(b<=110)
            & ((r-g)>15) & ((g-b)>5))

crops = {}
for name, cell in FRAMES.items():
    v = cells[cell]
    pad = 3
    x0=max(0,v['x0']-pad); y0=max(0,v['y0']-pad)
    x1=min(W,v['x1']+1+pad); y1=min(H,v['y1']+1+pad)
    arr = rgba[y0:y1, x0:x1].copy()  # uint8 HxWx4
    # recolor lado chica: pelo castano -> rosa, solo tercio superior, en RGB
    if name in RECOLORED:
        rgb = arr[:,:,:3].astype(int)
        top = int(arr.shape[0]*0.40)
        m = np.zeros(arr.shape[:2], dtype=bool)
        m[:top,:] = is_brown_rgb(rgb[:top,:,:])
        m &= (arr[:,:,3] > 0)
        r2 = np.clip(rgb[:,:,0]+70, 0, 255); g2 = np.clip(rgb[:,:,1]+30, 0, 255); b2 = np.clip(rgb[:,:,2]+120, 0, 255)
        arr[:,:,0] = np.where(m, r2, rgb[:,:,0]).astype(np.uint8)
        arr[:,:,1] = np.where(m, g2, rgb[:,:,1]).astype(np.uint8)
        arr[:,:,2] = np.where(m, b2, rgb[:,:,2]).astype(np.uint8)
        print(name, 'pixeles recolor rosa:', int(m.sum()))
    crop = Image.fromarray(arr, 'RGBA')
    crop.save(f'{OUT}/crops/{name}.png')
    # RGB bajo transparente -> vecino de figura mas cercano (sin halo blanco)
    alpha = arr[:,:,3]
    if (alpha == 0).any():
        _, idx = ndimage.distance_transform_edt(alpha == 0, return_indices=True)
        filled = arr[:,:,:3][tuple(idx)]
        arr2 = arr.copy()
        arr2[:,:,:3] = np.where((alpha==0)[:,:,None], filled, arr[:,:,:3])
        crop = Image.fromarray(arr2, 'RGBA')
    nw = max(1, round(crop.width*SCALE)); nh = max(1, round(crop.height*SCALE))
    small = crop.resize((nw, nh), Image.BOX)
    canvas = Image.new('RGBA', (FW, FH), (0,0,0,0))
    canvas.paste(small, ((FW-nw)//2, FH-nh), small)
    crops[name] = canvas

# --- paleta global k-means solo sobre pixeles de figura (opacos)
samples = []
for canvas in crops.values():
    arr = np.asarray(canvas).astype(float)
    m = arr[:,:,3] >= 128
    samples.append(arr[m][:,:3])
X = np.vstack(samples)
print('pixeles de figura para k-means:', X.shape[0])
# init determinista: k-means++ con seed 0
idx = rng.choice(len(X), size=NCOLORS, replace=False)
cent = X[idx].copy()
for it in range(30):
    d = ((X[:,None,:]-cent[None,:,:])**2).sum(axis=2)
    lab = d.argmin(axis=1)
    new = np.array([X[lab==k].mean(axis=0) if (lab==k).any() else cent[k] for k in range(NCOLORS)])
    if np.allclose(new, cent): break
    cent = new
pal_rgb = [tuple(int(round(c)) for c in col) for col in cent]
pal_rgb = sorted(set(pal_rgb))
print('paleta k-means (%d colores):' % len(pal_rgb))
for i,c in enumerate(pal_rgb): print(f'  {i+1}: #{c[0]:02X}{c[1]:02X}{c[2]:02X}')
NC = len(pal_rgb)
pal_arr = np.array(pal_rgb, dtype=float)
def quantize_frame(canvas):
    arr = np.asarray(canvas).astype(int)
    alpha = arr[:,:,3]
    opaque = alpha >= 128
    rgb = arr[:,:,:3].astype(float)
    d = ((rgb[:,:,None,:]-pal_arr[None,None,:,:])**2).sum(axis=3)
    best = d.argmin(axis=2) + 1
    best = np.where(opaque, best, 0).astype(np.uint8)
    return best
frames_idx = {name: quantize_frame(canvas) for name, canvas in crops.items()}
def encode(f):
    return ''.join(ALPHABET[int(v)] for v in f.flatten())
encoded = {n: encode(f) for n, f in frames_idx.items()}
for n, f in frames_idx.items():
    print(n, 'opacos', int((f>0).sum()), '/', FW*FH, 'colores usados', len(set(f.flatten().tolist())))
# indices de pelo para tests
def is_brown(c):
    r,g,b = c; return 70<=r<=190 and 40<=g<=140 and 20<=b<=110 and r>g+15 and g>b+5
def is_pink(c):
    r,g,b = c; return r>165 and (r-g)>40 and b>90
brown_idx = [i+1 for i,c in enumerate(pal_rgb) if is_brown(c)]
pink_idx  = [i+1 for i,c in enumerate(pal_rgb) if is_pink(c)]
print('indices pelo castano:', brown_idx, 'pelo rosa:', pink_idx)
json.dump({'palette': [list(c) for c in pal_rgb], 'frames': encoded,
           'mapping': FRAMES, 'brown_idx': brown_idx, 'pink_idx': pink_idx},
          open(f'{OUT}/avatar_data.json','w'), indent=1)
# --- vista previa
try: font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 15)
except: font = ImageFont.load_default()
names = list(FRAMES)
labels = {'BOY_FRONT_A':'BOY FRONT A','BOY_FRONT_B':'BOY FRONT B','BOY_BACK_A':'BOY BACK A',
 'BOY_BACK_B':'BOY BACK B','BOY_SIDE_A':'BOY SIDE A','BOY_SIDE_B':'BOY SIDE B',
 'GIRL_FRONT_A':'GIRL FRONT A','GIRL_FRONT_B':'GIRL FRONT B','GIRL_BACK_A':'GIRL BACK A',
 'GIRL_BACK_B':'GIRL BACK B','GIRL_SIDE_A':'GIRL SIDE A *','GIRL_SIDE_B':'GIRL SIDE B *'}
sheet = Image.new('RGB', (6*170+10, 2*300+10), 'white')
d = ImageDraw.Draw(sheet)
for i, name in enumerate(names):
    f = frames_idx[name]
    img = Image.new('RGBA', (FW,FH), (0,0,0,0))
    px = img.load()
    for y in range(FH):
        for x in range(FW):
            v = int(f[y,x])
            px[x,y] = (0,0,0,0) if v==0 else (*pal_rgb[v-1], 255)
    big = img.resize((FW*4, FH*4), Image.NEAREST)
    bg = Image.new('RGB', big.size, (245,245,245))
    bg.paste(big, (0,0), big)
    X = 10+(i%6)*170; Y = 10+(i//6)*300
    sheet.paste(bg, (X, Y))
    d.text((X, Y+FH*4+8), labels[name], fill=(180,0,0), font=font)
d.text((10, 2*300-6), '* lado chica = lado chico con pelo rosa (retoque)', fill=(100,100,100), font=font)
sheet.save(f'{OUT}/preview_contact.png')
gif_frames = []
seq = ['BOY_FRONT_A','BOY_FRONT_B','BOY_SIDE_A','BOY_SIDE_B','BOY_BACK_A','BOY_BACK_B',
       'GIRL_FRONT_A','GIRL_FRONT_B','GIRL_SIDE_A','GIRL_SIDE_B','GIRL_BACK_A','GIRL_BACK_B']
for name in seq:
    f = frames_idx[name]
    img = Image.new('RGBA', (FW,FH), (0,0,0,0))
    px = img.load()
    for y in range(FH):
        for x in range(FW):
            v = int(f[y,x])
            px[x,y] = (0,0,0,0) if v==0 else (*pal_rgb[v-1], 255)
    big = img.resize((FW*5, FH*5), Image.NEAREST)
    bg = Image.new('RGB', big.size, 'white')
    bg.paste(big, (0,0), big)
    gif_frames.append(bg)
gif_frames[0].save(f'{OUT}/walk_preview.gif', save_all=True, append_images=gif_frames[1:],
                   duration=220, loop=0)
print('OK: preview_contact.png, walk_preview.gif, avatar_data.json')
