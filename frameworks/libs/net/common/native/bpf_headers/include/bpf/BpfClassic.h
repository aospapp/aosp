/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

// Accept the full packet
#define BPF_ACCEPT BPF_STMT(BPF_RET | BPF_K, 0xFFFFFFFF)

// Reject the packet
#define BPF_REJECT BPF_STMT(BPF_RET | BPF_K, 0)

// *TWO* instructions: compare and if equal jump over the reject statement
#define BPF2_REJECT_IF_NOT_EQUAL(v) \
	BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (v), 1, 0), \
	BPF_REJECT

// 8-bit load relative to start of link layer (mac/ethernet) header.
#define BPF_LOAD_MAC_RELATIVE_U8(ofs) \
	BPF_STMT(BPF_LD | BPF_B | BPF_ABS, (__u32)SKF_LL_OFF + (ofs))

// Big/Network Endian 16-bit load relative to start of link layer (mac/ethernet) header.
#define BPF_LOAD_MAC_RELATIVE_BE16(ofs) \
	BPF_STMT(BPF_LD | BPF_H | BPF_ABS, (__u32)SKF_LL_OFF + (ofs))

// Big/Network Endian 32-bit load relative to start of link layer (mac/ethernet) header.
#define BPF_LOAD_MAC_RELATIVE_BE32(ofs) \
	BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (__u32)SKF_LL_OFF + (ofs))

// 8-bit load relative to start of network (IPv4/IPv6) header.
#define BPF_LOAD_NET_RELATIVE_U8(ofs) \
	BPF_STMT(BPF_LD | BPF_B | BPF_ABS, (__u32)SKF_NET_OFF + (ofs))

// Big/Network Endian 16-bit load relative to start of network (IPv4/IPv6) header.
#define BPF_LOAD_NET_RELATIVE_BE16(ofs) \
	BPF_STMT(BPF_LD | BPF_H | BPF_ABS, (__u32)SKF_NET_OFF + (ofs))

// Big/Network Endian 32-bit load relative to start of network (IPv4/IPv6) header.
#define BPF_LOAD_NET_RELATIVE_BE32(ofs) \
	BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (__u32)SKF_NET_OFF + (ofs))

#define field_sizeof(struct_type,field) sizeof(((struct_type *)0)->field)

// 8-bit load from IPv4 header field.
#define BPF_LOAD_IPV4_U8(field) \
	BPF_LOAD_NET_RELATIVE_U8(({ \
	  _Static_assert(field_sizeof(struct iphdr, field) == 1, "field of wrong size"); \
	  offsetof(iphdr, field); \
	}))

// Big/Network Endian 16-bit load from IPv4 header field.
#define BPF_LOAD_IPV4_BE16(field) \
	BPF_LOAD_NET_RELATIVE_BE16(({ \
	  _Static_assert(field_sizeof(struct iphdr, field) == 2, "field of wrong size"); \
	  offsetof(iphdr, field); \
	}))

// Big/Network Endian 32-bit load from IPv4 header field.
#define BPF_LOAD_IPV4_BE32(field) \
	BPF_LOAD_NET_RELATIVE_BE32(({ \
	  _Static_assert(field_sizeof(struct iphdr, field) == 4, "field of wrong size"); \
	  offsetof(iphdr, field); \
	}))

// 8-bit load from IPv6 header field.
#define BPF_LOAD_IPV6_U8(field) \
	BPF_LOAD_NET_RELATIVE_U8(({ \
	  _Static_assert(field_sizeof(struct ipv6hdr, field) == 1, "field of wrong size"); \
	  offsetof(ipv6hdr, field); \
	}))

// Big/Network Endian 16-bit load from IPv6 header field.
#define BPF_LOAD_IPV6_BE16(field) \
	BPF_LOAD_NET_RELATIVE_BE16(({ \
	  _Static_assert(field_sizeof(struct ipv6hdr, field) == 2, "field of wrong size"); \
	  offsetof(ipv6hdr, field); \
	}))

// Big/Network Endian 32-bit load from IPv6 header field.
#define BPF_LOAD_IPV6_BE32(field) \
	BPF_LOAD_NET_RELATIVE_BE32(({ \
	  _Static_assert(field_sizeof(struct ipv6hdr, field) == 4, "field of wrong size"); \
	  offsetof(ipv6hdr, field); \
	}))
