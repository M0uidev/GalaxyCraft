// GalaxyCraft's own boot (see Boot.h). The file selector's nerves are r13-relative objects with
// no symbols (r13 = 0x807D7320, read from __init_registers); their order comes from
// __sinit_\FileSelector_cpp. Mapped in the dev harness on 2026-10-05.
#include "Boot.h"

#include "syati.h"

#include "CodePatch.h"
#include "galaxycraft_protocol.h"

extern "C" void control__12FileSelectorFv(void* self);
extern "C" bool isNerve__9LiveActorCFPC5Nerve(const void* self, const void* nerve);
extern "C" void setNerve__9LiveActorFPC5Nerve(void* self, const void* nerve);
extern "C" long getNerveStep__9LiveActorCFv(const void* self);
extern "C" void* getActor__14LiveActorGroupCFi(const void* group, int i);
extern "C" void onSelect__12FileSelectorFP14FileSelectItem(void* self, void* item);
extern "C" bool isNew__14FileSelectItemCFv(const void* item);
extern "C" void kill__20TitleSequenceProductFv(void* self);
extern "C" void forceKill__13SysInfoWindowFv(void* self);
extern "C" bool isActive__20TitleSequenceProductCFv(const void* self);
extern "C" void requestChangeStage__20GameSequenceFunctionFPCcllRC10JMapIdInfo(const char*, long, long, const void*);
extern "C" void* getGameSequenceInGame__20GameSequenceFunctionFv();
extern "C" void setInStage__18GameSequenceInGameFPCc(void* self, const char* name);
extern "C" void setInGame__12GameSequenceFv(void* self);
extern "C" void resetPlayResultHolders__20GameSequenceFunctionFv();
extern "C" bool isOnGameEventFlagNormalEnding__2MRFv();
extern "C" void forceMarioPlayer__16GameDataFunctionFv();

namespace
{
const u32 R13 = 0x807D7320;
// FileSelector nerves, as r13 - N.
const int NRV_TITLE = 15808, NRV_CHOOSE = 15784, NRV_CHOSEN = 15764, NRV_START = 15760;
// A new file: "create a new game file?" (yes: create), then the file is made and its icon is
// chosen (Mii select start), which we skip: the file keeps the default icon.
const int NRV_ASK_CREATE = 15748, NRV_CREATE = 15744, NRV_PICK_ICON = 15736;
const int NRV_FIRST = 15808, NRV_LAST = 15672;
// FileSelector fields: its TitleSequenceProduct and the group of file items.
const u32 SELECTOR_TITLE = 204, SELECTOR_ITEMS = 152, SELECTOR_WINDOW = 168;
const int FILES = 3;
// The title's logo shows this long before it is dismissed (frames).
const long TITLE_FRAMES = 30;
// The chosen file turns to the front before Start (frames).
const long CHOSEN_FRAMES = 20;
// requestChangeStageAfterFileSelect, whose first instruction is replaced (stwu r1,-16(r1)).
const u32 AFTER_FILE_SELECT = 0x804D62C0;
const u32 AFTER_FILE_SELECT_FIRST = 0x9421FFF0;

bool gSpaceStage, gTeleported;
uint32_t gMusicFrames;  // BootMusicFrames
u32 gOriginal[2];  // the replaced instruction and a branch back: the game's own function
bool gOriginalReady;

bool On()
{
  return (BootHostFlags() & GXC_MBX_BOOT_SPACE) != 0;
}

const void* Nrv(int off)
{
  return reinterpret_cast<const void*>(R13 - off);
}

int CurrentNerve(const void* selector)
{
  for (int off = NRV_FIRST; off >= NRV_LAST; off -= 4)
    if (isNerve__9LiveActorCFPC5Nerve(selector, Nrv(off)))
      return off;
  return 0;
}

void SelectorControl(void* self)
{
  control__12FileSelectorFv(self);
  uint32_t* dbg = BootDebugWords();
  const int nerve = CurrentNerve(self);
  dbg[0] = reinterpret_cast<u32>(self);
  dbg[1] = nerve;
  dbg[2] = getNerveStep__9LiveActorCFv(self);
  if (!On())
    return;
  u8* me = static_cast<u8*>(self);
  void* title = *reinterpret_cast<void**>(me + SELECTOR_TITLE);
  if (nerve == NRV_TITLE && title && isActive__20TitleSequenceProductCFv(title) &&
      getNerveStep__9LiveActorCFv(self) > TITLE_FRAMES)
  {
    kill__20TitleSequenceProductFv(title);
    dbg[3] |= 1;
  }
  else if (nerve == NRV_CHOOSE && getNerveStep__9LiveActorCFv(self) > 1)
  {
    // The first file that exists; with none, the first one (made new).
    const void* group = *reinterpret_cast<void**>(me + SELECTOR_ITEMS);
    void* pick = getActor__14LiveActorGroupCFi(group, 0);
    for (int i = 0; i < FILES; i++)
    {
      void* item = getActor__14LiveActorGroupCFi(group, i);
      if (item && !isNew__14FileSelectItemCFv(item))
      {
        pick = item;
        break;
      }
    }
    if (pick)
    {
      onSelect__12FileSelectorFP14FileSelectItem(self, pick);
      dbg[3] |= 2;
    }
  }
  else if (nerve == NRV_ASK_CREATE && getNerveStep__9LiveActorCFv(self) > CHOSEN_FRAMES)
  {
    if (void* window = *reinterpret_cast<void**>(me + SELECTOR_WINDOW))
      forceKill__13SysInfoWindowFv(window);
    setNerve__9LiveActorFPC5Nerve(self, Nrv(NRV_CREATE));
    dbg[3] |= 16;
  }
  else if (nerve == NRV_PICK_ICON)
  {
    setNerve__9LiveActorFPC5Nerve(self, Nrv(NRV_CHOSEN));
    dbg[3] |= 32;
  }
  else if (nerve == NRV_CHOSEN && getNerveStep__9LiveActorCFv(self) > CHOSEN_FRAMES)
  {
    setNerve__9LiveActorFPC5Nerve(self, Nrv(NRV_START));
    dbg[3] |= 4;
  }
}

// The game's requestChangeStageAfterFileSelect, run from the copy of its first instruction.
void Original()
{
  if (!gOriginalReady)
  {
    gOriginal[0] = AFTER_FILE_SELECT_FIRST;
    gOriginal[1] = gxc::EncodeBranch(reinterpret_cast<u32>(&gOriginal[1]), AFTER_FILE_SELECT + 4);
    DCFlushRange(gOriginal, sizeof(gOriginal));
    ICInvalidateRange(gOriginal, sizeof(gOriginal));
    gOriginalReady = true;
  }
  reinterpret_cast<void (*)()>(reinterpret_cast<u32>(gOriginal))();
}

// requestChangeStageAfterFileSelect: GalaxyCraftSpace when booting by ourselves, with the same
// setup the game's does first (in game, play results reset, Mario as the player).
void AfterFileSelect()
{
  if (!On())
  {
    Original();
    return;
  }
  void* system = *reinterpret_cast<void**>(R13 - 25980);
  setInGame__12GameSequenceFv(**reinterpret_cast<void***>(static_cast<u8*>(system) + 12));
  resetPlayResultHolders__20GameSequenceFunctionFv();
  if (!isOnGameEventFlagNormalEnding__2MRFv())
    forceMarioPlayer__16GameDataFunctionFv();
  requestChangeStage__20GameSequenceFunctionFPCcllRC10JMapIdInfo(GXC_SPACE_STAGE, 1, -1, 0);
  setInStage__18GameSequenceInGameFPCc(getGameSequenceInGame__20GameSequenceFunctionFv(), GXC_SPACE_STAGE);
  BootDebugWords()[3] |= 8;
}

bool Same(const char* a, const char* b)
{
  while (*a && *a == *b)
    a++, b++;
  return *a == *b;
}
}  // namespace

void BootStage(const char* stage)
{
  gSpaceStage = stage && Same(stage, GXC_SPACE_STAGE);
  gTeleported = false;
}

bool BootHoldMario()
{
  if (!gSpaceStage)
    return false;
  if (gTeleported)
    return false;
  MR::setPlayerPos(TVec3f(0.f, 0.f, 0.f));
  TVec3f* v = MR::getPlayerVelocity();
  if (v)
    v->set(0.f, 0.f, 0.f);
  return true;
}

void BootTeleported()
{
  gTeleported = true;
}

void BootPlanetsDropped()
{
  gTeleported = false;
}

uint32_t BootMusicFrames()
{
  const bool playing = gSpaceStage && MR::isPlayingStageBgm();
  if (playing)
    gMusicFrames++;
  return gMusicFrames << 1 | (playing ? 1u : 0u);
}

// FileSelector's vtable: control (+ 0x50).
kmWritePointer(0x806974C0 + 0x50, SelectorControl);
kmBranch(0x804D62C0, AfterFileSelect);
