// What the player holds in Minecraft, in Steve's right hand inside SMG2: Minecraft's model of it
// (a cube, or the sprite as a flat item; core/HeldMesh) placed on his forearm as Minecraft's third
// person places it (build/gen/held.h, from Mario's skeleton by tools/steve/build.py), so it follows
// Mario's animations. Drawn only while Steve is: not in first person, where Minecraft draws the hand.
#include "syati.h"

#include "HeldItem.h"
#include "HeldMesh.h"
#include "ViewMath.h"
#include "VoxelPlanet.h"
#include "held.h"

namespace
{
// Two of each, used in turn: the GPU may still be reading last frame's.
struct Model
{
  u8* dl;  // HELD_DL_MAX bytes
  u8* sprite;  // 16x64 RGB5A3 (HELD_SPRITE_BYTES)
};
Model gModels[2] = {{0, 0}, {0, 0}};
u32 gNext = 0;
const Model* gDraw = 0;  // null: nothing in hand
u32 gDrawSize = 0;
u32 gKind = 0;
const LiveActor* gMario = 0;
bool gShown = false;

class HeldItemActor : public LiveActor
{
public:
  HeldItemActor() : LiveActor("GxcHeldItem") {}

  virtual void init(const JMapInfoIter&)
  {
    MR::connectToScene(this, 0x21, -1, -1, 0x0E);  // MovementType_MapObj, DrawType_ElectricRail
    MR::invalidateClipping(this);
    makeActorAppeared();
  }

  virtual void draw() const
  {
    if (!gDraw || !gShown || !gMario)
      return;
    MR::loadProjectionMtx();  // as VoxelPlanet's draw: another pass may have left its own
    const MtxPtr joint = MR::getJointMtx(gMario, GXC_HELD_JOINT);
    if (!joint)
      return;
    f32 j[12], view[12], arm[12], pos[12];
    const MtxPtr cam = MR::getCameraViewMtx();
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
      {
        j[4 * r + c] = joint[r][c];
        view[4 * r + c] = cam[r][c];
      }
    gxc::Mul34(j, gHeldMtx[gKind - 1], arm);
    gxc::Mul34(view, arm, pos);

    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    // As core/HeldMesh writes them: half texels, the face's shade, 128ths of the sprite.
    GXSetVtxAttrFmt(GX_VTXFMT4, GX_VA_POS, GX_POS_XYZ, GX_U8, 1);
    GXSetVtxAttrFmt(GX_VTXFMT4, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetVtxAttrFmt(GX_VTXFMT4, GX_VA_TEX0, GX_TEX_ST, GX_U8, gxc::HELD_TEX_FRAC);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXTexObj tex;
    GXInitTexObj(&tex, gDraw->sprite, gxc::HELD_SPRITE, gxc::HELD_SPRITE * gxc::HELD_BANDS, GX_TF_RGB5A3, GX_CLAMP,
                 GX_CLAMP, GX_FALSE);
    GXInitTexObjLOD(&tex, GX_NEAR, GX_NEAR, 0.f, 0.f, 0.f, GX_FALSE, GX_FALSE, GX_ANISO_1);
    GXLoadTexObj(&tex, GX_TEXMAP0);
    GXSetNumIndStages(0);
    GXSetTevDirect(GX_TEVSTAGE0);
    GXSetNumTevStages(1);
    GXSetTevSwapMode(GX_TEVSTAGE0, GX_TEV_SWAP0, GX_TEV_SWAP0);
    GXSetTevSwapModeTable(GX_TEV_SWAP0, GX_CH_RED, GX_CH_GREEN, GX_CH_BLUE, GX_CH_ALPHA);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_MODULATE);
    GXColor black = {0, 0, 0, 0};
    GXSetFog(GX_FOG_NONE, 0.f, 0.f, 0.f, 0.f, black);
    // The sprite's holes are cut out (Minecraft's cutout), and must not hide what is behind.
    GXSetAlphaCompare(GX_GREATER, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
    GXSetZCompLoc(GX_FALSE);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_NOOP);
    GXSetCullMode(GX_CULL_NONE);  // the faces are not wound one way
    GXSetColorUpdate(GX_TRUE);
    GXSetAlphaUpdate(GX_FALSE);
    GXSetDstAlpha(GX_FALSE, 0);
    GXSetCurrentMtx(GX_PNMTX0);
    GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
    GXCallDisplayList(gDraw->dl, gDrawSize);
    GXSetZCompLoc(GX_TRUE);
  }
};
}  // namespace

void HeldItemCreate()
{
  // A new scene: the old one's heap (and the models in it) is gone.
  gModels[0].dl = gModels[0].sprite = gModels[1].dl = gModels[1].sprite = 0;
  gDraw = 0;
  gMario = 0;
  gShown = false;
  HeldItemActor* actor = new HeldItemActor();
  actor->initWithoutIter();
}

void HeldItemSet(const gxc::InboxHeld& held)
{
  gDraw = 0;
  if (held.kind == gxc::HELD_NONE)
    return;
  Model& m = gModels[gNext];
  if (!m.dl)
    m.dl = VoxelPlanetAlloc32(gxc::HELD_DL_MAX);
  if (!m.sprite)
    m.sprite = VoxelPlanetAlloc32(gxc::HELD_SPRITE_BYTES);
  if (!m.dl || !m.sprite)
    return;
  memcpy(m.sprite, held.sprite, gxc::HELD_SPRITE_BYTES);
  DCFlushRange(m.sprite, gxc::HELD_SPRITE_BYTES);
  const u32 size = gxc::HeldMesh(held.kind, m.sprite, GX_VTXFMT4, m.dl, gxc::HELD_DL_MAX);
  if (size == 0)
    return;
  DCFlushRange(m.dl, size);
  gDraw = &m;
  gDrawSize = size;
  gKind = held.kind;
  gNext ^= 1;
}

void HeldItemFrame(const LiveActor* mario, bool shown)
{
  gMario = mario;
  gShown = shown;
}

uint32_t HeldItemKind()
{
  return gDraw ? gKind : 0;
}
