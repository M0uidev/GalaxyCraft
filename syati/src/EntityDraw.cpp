// Entities on the planet, drawn by the game: the mod cuts Minecraft's models into rigid pieces
// (display lists in pixels of the model) and sends them once per scene with their textures; each
// frame it sends only which piece goes where (a matrix to the galaxy) and what color is mixed over
// it (red when hurt, white when TNT flashes). See GXC_ENT_* in protocol/galaxycraft_protocol.h.
#include "syati.h"

#include "EntityDraw.h"
#include "ViewMath.h"
#include "VoxelPlanet.h"

namespace
{
struct Skin
{
  u8* data;
  u32 bytes, width, height;
};
struct Model
{
  u8* dl;
  u32 cap, size;
};
Skin gSkins[gxc::ENT_MAX_SKINS];
Model gModels[gxc::ENT_MAX_MODELS];
u8* gList = 0;  // ENT_MAX x ENT_BYTES: the newest frame's entities
u32 gCount = 0;
u32 gDrawn = 0;

f32 ReadF32(const u8* p)
{
  const u32 v = gxc::ReadBE32(p);
  f32 f;
  memcpy(&f, &v, 4);
  return f;
}

class EntityDrawActor : public LiveActor
{
public:
  EntityDrawActor() : LiveActor("GxcEntities") {}

  virtual void init(const JMapInfoIter&)
  {
    MR::connectToScene(this, 0x21, -1, -1, 0x0E);  // MovementType_MapObj, DrawType_ElectricRail
    MR::invalidateClipping(this);
    makeActorAppeared();
  }

  virtual void draw() const
  {
    gDrawn = 0;
    if (!gCount || !gList)
      return;
    MR::loadProjectionMtx();  // as VoxelPlanet's draw: another pass may have left its own
    f32 view[12];
    const MtxPtr cam = MR::getCameraViewMtx();
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
        view[4 * r + c] = cam[r][c];

    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT3, GX_VA_POS, GX_POS_XYZ, GX_S16, 4);
    GXSetVtxAttrFmt(GX_VTXFMT3, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetVtxAttrFmt(GX_VTXFMT3, GX_VA_TEX0, GX_TEX_ST, GX_S16, 12);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXSetNumIndStages(0);
    GXSetTevDirect(GX_TEVSTAGE0);
    GXSetTevDirect(GX_TEVSTAGE1);
    GXSetNumTevStages(2);
    GXSetTevSwapMode(GX_TEVSTAGE0, GX_TEV_SWAP0, GX_TEV_SWAP0);
    GXSetTevSwapMode(GX_TEVSTAGE1, GX_TEV_SWAP0, GX_TEV_SWAP0);
    GXSetTevSwapModeTable(GX_TEV_SWAP0, GX_CH_RED, GX_CH_GREEN, GX_CH_BLUE, GX_CH_ALPHA);
    // Texture times the face's shade, then the overlay color mixed in by its alpha (KONST: K0's A).
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_MODULATE);
    GXSetTevOrder(GX_TEVSTAGE1, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR_NULL);
    GXSetTevKColorSel(GX_TEVSTAGE1, GX_TEV_KCSEL_K0_A);
    GXSetTevColorIn(GX_TEVSTAGE1, GX_CC_CPREV, GX_CC_C0, GX_CC_KONST, GX_CC_ZERO);
    GXSetTevColorOp(GX_TEVSTAGE1, GX_TEV_ADD, GX_TB_ZERO, GX_CS_SCALE_1, GX_TRUE, GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE1, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_APREV);
    GXSetTevAlphaOp(GX_TEVSTAGE1, GX_TEV_ADD, GX_TB_ZERO, GX_CS_SCALE_1, GX_TRUE, GX_TEVPREV);
    GXColor black = {0, 0, 0, 0};
    GXSetFog(GX_FOG_NONE, 0.f, 0.f, 0.f, 0.f, black);
    // Skins cut their holes out (Minecraft's cutout), which must not hide what is behind.
    GXSetAlphaCompare(GX_GREATER, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
    GXSetZCompLoc(GX_FALSE);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_NOOP);
    GXSetCullMode(GX_CULL_NONE);
    GXSetColorUpdate(GX_TRUE);
    GXSetAlphaUpdate(GX_FALSE);
    GXSetDstAlpha(GX_FALSE, 0);
    GXSetCurrentMtx(GX_PNMTX0);

    u32 loaded = 0xFFFFFFFF;
    for (u32 n = 0; n < gCount; n++)
    {
      const u8* e = gList + n * gxc::ENT_BYTES;
      const u32 raw = (u32(e[0]) << 8) | e[1], model = raw & 0x7FFF, skin = (u32(e[2]) << 8) | e[3];
      if (model >= gxc::ENT_MAX_MODELS || skin >= gxc::ENT_MAX_SKINS || !gModels[model].size || !gSkins[skin].data)
        continue;  // not here yet (or lost): drawn once it is
      if (skin != loaded)
      {
        const Skin& s = gSkins[skin];
        GXTexObj tex;
        GXInitTexObj(&tex, s.data, s.width, s.height, GX_TF_RGB5A3, GX_CLAMP, GX_CLAMP, GX_FALSE);
        GXInitTexObjLOD(&tex, GX_NEAR, GX_NEAR, 0.f, 0.f, 0.f, GX_FALSE, GX_FALSE, GX_ANISO_1);
        GXLoadTexObj(&tex, GX_TEXMAP0);
        loaded = skin;
      }
      GXColor overlay = {e[4], e[5], e[6], e[7]};
      GXSetTevColor(GX_TEVREG0, overlay);
      GXSetTevKColor(GX_KCOLOR0, overlay);
      f32 m[12], pos[12];
      for (int k = 0; k < 12; k++)
        m[k] = ReadF32(e + 8 + 4 * k);
      gxc::Mul34(view, m, pos);
      if (raw & 0x8000)
      {
        // A particle: facing the camera, only its size kept (model y is down, view y up).
        const f32 size = gxc::Sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]);
        for (int r = 0; r < 3; r++)
          for (int c = 0; c < 3; c++)
            pos[4 * r + c] = r == c ? (r == 1 ? -size : size) : 0.f;
      }
      GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
      GXCallDisplayList(gModels[model].dl, gModels[model].size);
      gDrawn++;
    }
    GXSetNumTevStages(1);
    GXSetZCompLoc(GX_TRUE);
  }
};
}  // namespace

void EntityDrawCreate()
{
  // A new scene: the old one's heap (and everything in it) is gone; the mod sends it all again.
  memset(gSkins, 0, sizeof(gSkins));
  memset(gModels, 0, sizeof(gModels));
  gList = VoxelPlanetAlloc32(gxc::ENT_MAX * gxc::ENT_BYTES);
  gCount = 0;
  EntityDrawActor* actor = new EntityDrawActor();
  actor->initWithoutIter();
}

void EntityDrawSkin(const gxc::InboxSkin& skin)
{
  Skin& s = gSkins[skin.id];
  const u32 bytes = skin.width * skin.height * 2;
  if (!s.data || s.bytes < bytes)
  {
    s.data = VoxelPlanetAlloc32(bytes);
    s.bytes = s.data ? bytes : 0;
  }
  if (!s.data)
    return;
  memcpy(s.data, skin.data, bytes);
  DCFlushRange(s.data, bytes);
  s.width = skin.width;
  s.height = skin.height;
}

void EntityDrawModel(const gxc::InboxModel& model)
{
  Model& m = gModels[model.id];
  if (!m.dl || m.cap < model.dl_size)
  {
    m.size = 0;
    m.dl = VoxelPlanetAlloc32(model.dl_size);
    m.cap = m.dl ? model.dl_size : 0;
  }
  if (!m.dl)
    return;
  memcpy(m.dl, model.dl, model.dl_size);
  DCFlushRange(m.dl, model.dl_size);
  m.size = model.dl_size;
}

void EntityDrawFrame(const gxc::InboxEntities& entities)
{
  if (!gList)
    return;
  memcpy(gList, entities.list, entities.count * gxc::ENT_BYTES);
  gCount = entities.count;
}

uint32_t EntityDrawCount()
{
  return gDrawn;
}
