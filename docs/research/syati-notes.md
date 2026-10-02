# Notas para la Fase 3 (módulo Syati)

Fuente: headers de [SMGCommunity/Syati](https://github.com/SMGCommunity/Syati) (rama `main`, 2026-10-02).
Juego disponible localmente: `Super Mario Galaxy 2 (USA)` → región **SB4E**.

## Lo que cada pieza del spec usará

| Pieza (spec §3) | Función / dato de Syati | Header |
|---|---|---|
| GravityQuery | `bool MR::calcGravityVector(const NameObj*, const TVec3f& pos, TVec3f* out, GravityInfo*, u32 host)` — gravedad en un punto arbitrario | `Game/Util/GravityUtil.h` |
| GravityQuery (sin gravedad) | `MR::calcGravityVectorOrZero(...)` misma firma, devuelve 0 si no hay | idem |
| CameraDriver | `MR::setCameraViewMtx(const TPos3f&, bool, bool, const TVec3f&)`, `MR::setFovy(f32)`, `MR::getCameraViewMtx()` | `Game/Util/CameraUtil.h` |
| CollisionExporter | `CollisionParts::mMatrix` (`TMtx34f`, offset 0x4), `mBaseMatrix` (0x34), `mServer` (`KCollisionServer*`, 0xC4) | `Game/Map/CollisionParts.h` |
| CollisionExporter (lista) | `CollisionCategorizedKeeper` → `CollisionZone` (`mPartsCount` en 0x804) | `Game/Map/CollisionCategorizedKeeper.h` |
| MarioPuppet | `MarioActor` (pendiente: confirmar offsets de posición y la función de movimiento a enganchar) | `Game/Player/MarioActor.h` |

`KCollisionServer` está opaco en Syati (`u8 _0[0xC]`). En Petari (SMG1) su primer campo apunta
al header KCL en RAM; hay que confirmarlo en SMG2 con Dolphin (memory viewer) antes de depender
de él. Fallback del spec: leer el KCL del disco extraído.

## Toolchain (sin sudo)

- CodeWarrior PPC EABI 4.3.0.172 (`mwcceppc.exe`, se ejecuta con `wine`, ya instalado).
- Kamek (linker, .NET → `dotnet-sdk`).
- SyatiModuleBuildTool / SyatiManager para empaquetar el `CustomCode` y el loader (Riivolution).
