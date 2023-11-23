// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
// -*- Mode: C++ -*-
//
// Copyright (C) 2021 Oracle, Inc.
//
// Author: Jose E. Marchesi

/// @file
///
/// This file contains the declarations of the entry points to
/// de-serialize an instance of @ref abigail::corpus from a file in
/// elf format, containing CTF information.

#ifndef __ABG_CTF_READER_H__
#define __ABG_CTF_READER_H__

#include <ostream>
#include "abg-corpus.h"
#include "abg-suppression.h"
#include "abg-elf-reader-common.h"

#include "ctf-api.h"

namespace abigail
{
namespace ctf_reader
{

class read_context;
typedef shared_ptr<read_context> read_context_sptr;

read_context_sptr
create_read_context(const std::string& elf_path,
                    const vector<char**>& debug_info_root_paths,
                    ir::environment *env);
corpus_sptr
read_corpus(read_context *ctxt, elf_reader::status& status);

corpus_sptr
read_corpus(const read_context_sptr &ctxt, elf_reader::status &status);

corpus_sptr
read_and_add_corpus_to_group_from_elf(read_context*, corpus_group&, elf_reader::status&);

void
set_read_context_corpus_group(read_context& ctxt, corpus_group_sptr& group);

void
reset_read_context(read_context_sptr &ctxt,
                   const std::string&	elf_path,
                   const vector<char**>& debug_info_root_path,
                   ir::environment*	environment);
std::string
dic_type_key(ctf_dict_t *dic, ctf_id_t ctf_type);
} // end namespace ctf_reader
} // end namespace abigail

#endif // ! __ABG_CTF_READER_H__
