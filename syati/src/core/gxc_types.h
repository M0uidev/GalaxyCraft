#pragma once
// Fixed-width types shared by CodeWarrior (no standard headers under -nodefaults) and g++.
// Same spellings as Syati's revolution/types.h, so redeclaring them there is harmless.
typedef unsigned char u8;
typedef unsigned short u16;
#ifdef __MWERKS__
typedef unsigned long u32;  // 32-bit long on PowerPC, as in revolution/types.h
typedef signed long s32;
#else
typedef unsigned int u32;
typedef signed int s32;
#endif
typedef float f32;
