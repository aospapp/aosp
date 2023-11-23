// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
// -*- Mode: C++ -*-
//
// Copyright (C) 2021 Oracle, Inc.
//
// Author: Jose E. Marchesi

/// @file
///
/// This file contains the definitions of the entry points to
/// de-serialize an instance of @ref abigail::corpus from a file in
/// ELF format, containing CTF information.

#include "config.h"

#include <fcntl.h> /* For open(3) */
#include <iostream>
#include <memory>
#include <map>
#include <algorithm>

#include "ctf-api.h"

#include "abg-internal.h"
#include "abg-ir-priv.h"
#include "abg-elf-helpers.h"

// <headers defining libabigail's API go under here>
ABG_BEGIN_EXPORT_DECLARATIONS

#include "abg-ctf-reader.h"
#include "abg-libxml-utils.h"
#include "abg-reader.h"
#include "abg-corpus.h"
#include "abg-symtab-reader.h"
#include "abg-tools-utils.h"

ABG_END_EXPORT_DECLARATIONS
// </headers defining libabigail's API>

namespace abigail
{
namespace ctf_reader
{
using std::dynamic_pointer_cast;

class read_context
{
public:
  /// The name of the ELF file from which the CTF archive got
  /// extracted.
  string filename;

  /// The IR environment.
  ir::environment *ir_env;

  /// The CTF archive read from FILENAME.  If an archive couldn't
  /// be read from the file then this is NULL.
  ctf_archive_t *ctfa;

  /// A map associating CTF type ids with libabigail IR types.  This
  /// is used to reuse already generated types.
  unordered_map<string,type_base_sptr> types_map;

  /// A set associating unknown CTF type ids
  std::set<ctf_id_t> unknown_types_set;

  /// libelf handler for the ELF file from which we read the CTF data,
  /// and the corresponding file descriptor.
  Elf *elf_handler;
  int elf_fd;

  /// libelf handler for the ELF file from which we read the CTF data,
  /// and the corresponding file descriptor found in external .debug file
  Elf *elf_handler_dbg;
  int elf_fd_dbg;

  /// The symtab read from the ELF file.
  symtab_reader::symtab_sptr symtab;

  /// Raw contents of several sections from the ELF file.  These are
  /// used by libctf.
  ctf_sect_t ctf_sect;
  ctf_sect_t symtab_sect;
  ctf_sect_t strtab_sect;

  corpus_sptr			cur_corpus_;
  corpus_group_sptr		cur_corpus_group_;
  corpus::exported_decls_builder* exported_decls_builder_;
  // The set of directories under which to look for debug info.
  vector<char**>		debug_info_root_paths_;

  /// Setter of the exported decls builder object.
  ///
  /// Note that this @ref read_context is not responsible for the live
  /// time of the exported_decls_builder object.  The corpus is.
  ///
  /// @param b the new builder.
  void
  exported_decls_builder(corpus::exported_decls_builder* b)
  {exported_decls_builder_ = b;}

  /// Getter of the exported decls builder object.
  ///
  /// @return the exported decls builder.
  corpus::exported_decls_builder*
  exported_decls_builder()
  {return exported_decls_builder_;}

  /// If a given function decl is suitable for the set of exported
  /// functions of the current corpus, this function adds it to that
  /// set.
  ///
  /// @param fn the function to consider for inclusion into the set of
  /// exported functions of the current corpus.
  void
  maybe_add_fn_to_exported_decls(function_decl* fn)
  {
    if (fn)
      if (corpus::exported_decls_builder* b = exported_decls_builder())
	b->maybe_add_fn_to_exported_fns(fn);
  }

  /// If a given variable decl is suitable for the set of exported
  /// variables of the current corpus, this variable adds it to that
  /// set.
  ///
  /// @param fn the variable to consider for inclusion into the set of
  /// exported variables of the current corpus.
  void
  maybe_add_var_to_exported_decls(var_decl* var)
  {
    if (var)
      if (corpus::exported_decls_builder* b = exported_decls_builder())
	b->maybe_add_var_to_exported_vars(var);
  }

  /// Getter of the current corpus group being constructed.
  ///
  /// @return current the current corpus being constructed, if any, or
  /// nil.
  const corpus_group_sptr
  current_corpus_group() const
  {return cur_corpus_group_;}

  /// Test if there is a corpus group being built.
  ///
  /// @return if there is a corpus group being built, false otherwise.
  bool
  has_corpus_group() const
  {return bool(cur_corpus_group_);}

  /// Return the main corpus from the current corpus group, if any.
  ///
  /// @return the main corpus of the current corpus group, if any, nil
  /// if no corpus group is being constructed.
  corpus_sptr
  main_corpus_from_current_group()
  {
    if (cur_corpus_group_)
      return cur_corpus_group_->get_main_corpus();
    return corpus_sptr();
  }

  /// Test if the current corpus being built is the main corpus of the
  /// current corpus group.
  ///
  /// @return return true iff the current corpus being built is the
  /// main corpus of the current corpus group.
  bool
  current_corpus_is_main_corpus_from_current_group()
  {
    corpus_sptr main_corpus = main_corpus_from_current_group();

    if (main_corpus && main_corpus.get() == cur_corpus_.get())
      return true;

    return false;
  }

  /// Return true if the current corpus is part of a corpus group
  /// being built and if it's not the main corpus of the group.
  ///
  /// For instance, this would return true if we are loading a linux
  /// kernel *module* that is part of the current corpus group that is
  /// being built.  In this case, it means we should re-use types
  /// coming from the "vmlinux" binary that is the main corpus of the
  /// group.
  ///
  /// @return the corpus group the current corpus belongs to, if the
  /// current corpus is part of a corpus group being built. Nil otherwise.
  corpus_sptr
  should_reuse_type_from_corpus_group()
  {
    if (has_corpus_group())
      if (corpus_sptr main_corpus = main_corpus_from_current_group())
	if (!current_corpus_is_main_corpus_from_current_group())
	  return current_corpus_group();

    return corpus_sptr();
  }

  /// Associate a given CTF type ID with a given libabigail IR type.
  ///
  /// @param dic the dictionnary the type belongs to.
  ///
  /// @param ctf_type the type ID.
  ///
  /// @param type the type to associate to the ID.
  void
  add_type(ctf_dict_t *dic, ctf_id_t ctf_type, type_base_sptr type)
  {
    string key = dic_type_key(dic, ctf_type);
    types_map.insert(std::make_pair(key, type));
  }

  /// Insert a given CTF unknown type ID.
  ///
  /// @param ctf_type the unknown type ID to be added.
  void
  add_unknown_type(ctf_id_t ctf_type)
  {
    unknown_types_set.insert(ctf_type);
  }

  /// Lookup a given CTF type ID in the types map.
  ///
  /// @param dic the dictionnary the type belongs to.
  ///
  /// @param ctf_type the type ID of the type to lookup.
  type_base_sptr
  lookup_type(ctf_dict_t *dic, ctf_id_t ctf_type)
  {
    type_base_sptr result;
    std::string key = dic_type_key(dic, ctf_type);

    auto search = types_map.find(key);
    if (search != types_map.end())
      result = search->second;

    return result;
  }

  /// Lookup a given CTF unknown type ID in the unknown set.
  /// @param ctf_type the unknown type ID to lookup.
  bool
  lookup_unknown_type(ctf_id_t ctf_type)
  { return unknown_types_set.find(ctf_type) != unknown_types_set.end(); }

  /// Canonicalize all the types stored in the types map.
  void
  canonicalize_all_types(void)
  {
    for (auto t = types_map.begin(); t != types_map.end(); t++)
      canonicalize (t->second);
  }

  /// Constructor.
  ///
  /// @param elf_path the path to the ELF file.
  ///
  /// @param debug_info_root_paths vector with the paths
  /// to directories where .debug file is located.
  ///
  /// @param env the environment used by the current context.
  /// This environment contains resources needed by the reader and by
  /// the types and declarations that are to be created later.  Note
  /// that ABI artifacts that are to be compared all need to be
  /// created within the same environment.
  read_context(const string& elf_path,
               const vector<char**>& debug_info_root_paths,
               ir::environment *env) :
   ctfa(NULL)
  {
    initialize(elf_path, debug_info_root_paths, env);
  }

  /// Initializer of read_context.
  ///
  /// @param elf_path the path to the elf file the context is to be
  /// used for.
  ///
  /// @param debug_info_root_paths vector with the paths
  /// to directories where .debug file is located.
  ///
  /// @param environment the environment used by the current context.
  /// This environment contains resources needed by the reader and by
  /// the types and declarations that are to be created later.  Note
  /// that ABI artifacts that are to be compared all need to be
  /// created within the same environment.
  ///
  /// Please also note that the life time of this environment object
  /// must be greater than the life time of the resulting @ref
  /// read_context the context uses resources that are allocated in
  /// the environment.
  void
  initialize(const string& elf_path,
             const vector<char**>& debug_info_root_paths,
             ir::environment *env)
  {
    types_map.clear();
    filename = elf_path;
    ir_env = env;
    elf_handler = NULL;
    elf_handler_dbg = NULL;
    elf_fd = -1;
    elf_fd_dbg = -1;
    symtab.reset();
    cur_corpus_group_.reset();
    exported_decls_builder_ = 0;
    debug_info_root_paths_ = debug_info_root_paths;
  }

  ~read_context()
  {
    ctf_close(ctfa);
  }
}; // end class read_context.

/// Forward reference, needed because several of the process_ctf_*
/// functions below are indirectly recursive through this call.
static type_base_sptr lookup_type(read_context *ctxt,
                                  corpus_sptr corp,
                                  translation_unit_sptr tunit,
                                  ctf_dict_t *ctf_dictionary,
                                  ctf_id_t ctf_type);

/// Build and return a typedef libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the typedef.

static typedef_decl_sptr
process_ctf_typedef(read_context *ctxt,
                    corpus_sptr corp,
                    translation_unit_sptr tunit,
                    ctf_dict_t *ctf_dictionary,
                    ctf_id_t ctf_type)
{
  typedef_decl_sptr result;

  ctf_id_t ctf_utype = ctf_type_reference(ctf_dictionary, ctf_type);
  if (ctf_utype == CTF_ERR)
    return result;

  const char *typedef_name = ctf_type_name_raw(ctf_dictionary, ctf_type);
  if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
    if (result = lookup_typedef_type(typedef_name, *corp))
      return result;

  type_base_sptr utype = lookup_type(ctxt, corp, tunit,
                                     ctf_dictionary, ctf_utype);

  if (!utype)
    return result;

  result = dynamic_pointer_cast<typedef_decl>(ctxt->lookup_type(ctf_dictionary,
                                                                ctf_type));
  if (result)
    return result;

  result.reset(new typedef_decl(typedef_name, utype, location(),
                                typedef_name /* mangled_name */));

  /* If this typedef "names" an anonymous type, reflect this fact in
     the underlying type.  In C enum, struct and union types can be
     anonymous.  */
  if (is_anonymous_type(utype)
      && (is_enum_type(utype) || is_class_or_union_type(utype)))
    {
      decl_base_sptr decl = is_decl(utype);
      ABG_ASSERT(decl);
      decl->set_naming_typedef(result);
    }

  if (result)
    {
      add_decl_to_scope(result, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Build and return an integer or float type declaration libabigail
/// IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the type.

static type_decl_sptr
process_ctf_base_type(read_context *ctxt,
                      corpus_sptr corp,
                      translation_unit_sptr tunit,
                      ctf_dict_t *ctf_dictionary,
                      ctf_id_t ctf_type)
{
  type_decl_sptr result;

  ssize_t type_alignment = ctf_type_align(ctf_dictionary, ctf_type);
  const char *type_name = ctf_type_name_raw(ctf_dictionary, ctf_type);

  /* Get the type encoding and extract some useful properties of
     the type from it.  In case of any error, just ignore the
     type.  */
  ctf_encoding_t type_encoding;
  if (ctf_type_encoding(ctf_dictionary,
                         ctf_type,
                         &type_encoding))
    return result;

  /* Create the IR type corresponding to the CTF type.  */
  if (type_encoding.cte_bits == 0
      && type_encoding.cte_format == CTF_INT_SIGNED)
    {
      /* This is the `void' type.  */
      type_base_sptr void_type = ctxt->ir_env->get_void_type();
      decl_base_sptr type_declaration = get_type_declaration(void_type);
      result = is_type_decl(type_declaration);
      canonicalize(result);
    }
  else
    {
      if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
        {
          string normalized_type_name = type_name;
          integral_type int_type;
          if (parse_integral_type(type_name, int_type))
            normalized_type_name = int_type.to_string();
          if (result = lookup_basic_type(normalized_type_name, *corp))
            return result;
        }

      result = lookup_basic_type(type_name, *corp);
      if (!result)
        result.reset(new type_decl(ctxt->ir_env,
                                   type_name,
                                   type_encoding.cte_bits,
                                   type_alignment * 8 /* in bits */,
                                   location(),
                                   type_name /* mangled_name */));

    }

  if (result)
    {
      add_decl_to_scope(result, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Build the IR node for a variadic parameter type.
///
/// @param ctxt the read context to use.
///
/// @return the variadic parameter type.
static decl_base_sptr
build_ir_node_for_variadic_parameter_type(read_context &ctxt,
                                          translation_unit_sptr tunit)
{

  ir::environment* env = ctxt.ir_env;
  ABG_ASSERT(env);
  type_base_sptr t = env->get_variadic_parameter_type();
  decl_base_sptr type_declaration = get_type_declaration(t);
  if (!has_scope(type_declaration))
    add_decl_to_scope(type_declaration, tunit->get_global_scope());
  canonicalize(t);
  return type_declaration;
}

/// Build and return a function type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the function type.

static function_type_sptr
process_ctf_function_type(read_context *ctxt,
                          corpus_sptr corp,
                          translation_unit_sptr tunit,
                          ctf_dict_t *ctf_dictionary,
                          ctf_id_t ctf_type)
{
  function_type_sptr result;

  /* Fetch the function type info from the CTF type.  */
  ctf_funcinfo_t funcinfo;
  ctf_func_type_info(ctf_dictionary, ctf_type, &funcinfo);
  int vararg_p = funcinfo.ctc_flags & CTF_FUNC_VARARG;

  /* Take care first of the result type.  */
  ctf_id_t ctf_ret_type = funcinfo.ctc_return;
  type_base_sptr ret_type = lookup_type(ctxt, corp, tunit,
                                        ctf_dictionary, ctf_ret_type);
  if (!ret_type)
    return result;

  /* Now process the argument types.  */
  int argc = funcinfo.ctc_argc;
  std::vector<ctf_id_t> argv(argc);
  if (static_cast<ctf_id_t>(ctf_func_type_args(ctf_dictionary, ctf_type,
					       argc, argv.data())) == CTF_ERR)
    return result;

  function_decl::parameters function_parms;
  for (int i = 0; i < argc; i++)
    {
      ctf_id_t ctf_arg_type = argv[i];
      type_base_sptr arg_type = lookup_type(ctxt, corp, tunit,
                                            ctf_dictionary, ctf_arg_type);
      if (!arg_type)
        return result;

      function_decl::parameter_sptr parm
        (new function_decl::parameter(arg_type, "",
                                      location(),
                                      false,
                                      false /* is_artificial */));
      function_parms.push_back(parm);
    }

  if (vararg_p)
    {
      type_base_sptr arg_type =
       is_type(build_ir_node_for_variadic_parameter_type(*ctxt, tunit));

      function_decl::parameter_sptr parm
       (new function_decl::parameter(arg_type, "",
                                     location(),
                                     true,
                                     false /* is_artificial */));
      function_parms.push_back(parm);
    }

  result = dynamic_pointer_cast<function_type>(ctxt->lookup_type(ctf_dictionary,
                                                                 ctf_type));
  if (result)
    return result;

  /* Ok now the function type itself.  */
  result.reset(new function_type(ret_type,
                                 function_parms,
                                 tunit->get_address_size(),
                                 ctf_type_align(ctf_dictionary, ctf_type)));

  if (result)
    {
      tunit->bind_function_type_life_time(result);
      result->set_is_artificial(true);
      decl_base_sptr function_type_decl = get_type_declaration(result);
      add_decl_to_scope(function_type_decl, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Add member information to a IR struct or union type.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
/// @param sou the IR struct or union type to which add the members.

static void
process_ctf_sou_members(read_context *ctxt,
                        corpus_sptr corp,
                        translation_unit_sptr tunit,
                        ctf_dict_t *ctf_dictionary,
                        ctf_id_t ctf_type,
                        class_or_union_sptr sou)
{
  ssize_t member_size;
  ctf_next_t *member_next = NULL;
  const char *member_name = NULL;
  ctf_id_t member_ctf_type;

  while ((member_size = ctf_member_next(ctf_dictionary, ctf_type,
                                        &member_next, &member_name,
                                        &member_ctf_type,
                                        0 /* flags */)) >= 0)
    {
      ctf_membinfo_t membinfo;

      if (static_cast<ctf_id_t>(ctf_member_info(ctf_dictionary,
						ctf_type,
						member_name,
						&membinfo)) == CTF_ERR)
        return;

      /* Build the IR for the member's type.  */
      type_base_sptr member_type = lookup_type(ctxt, corp, tunit,
                                               ctf_dictionary,
                                               member_ctf_type);
      if (!member_type)
        /* Ignore this member.  */
        continue;

      /* Create a declaration IR node for the member and add it to the
         struct type.  */
      var_decl_sptr data_member_decl(new var_decl(member_name,
                                                  member_type,
                                                  location(),
                                                  member_name));
      sou->add_data_member(data_member_decl,
                           public_access,
                           true /* is_laid_out */,
                           false /* is_static */,
                           membinfo.ctm_offset);
    }
  if (ctf_errno(ctf_dictionary) != ECTF_NEXT_END)
    fprintf(stderr, "ERROR from ctf_member_next\n");
}

/// Create a declaration-only union or struct type and add it to the
/// IR.
///
/// @param ctxt the read context.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
/// @return the resulting IR node created.

static type_base_sptr
process_ctf_forward_type(read_context *ctxt,
                         translation_unit_sptr tunit,
                         ctf_dict_t *ctf_dictionary,
                         ctf_id_t ctf_type)
{
  decl_base_sptr result;
  std::string type_name = ctf_type_name_raw(ctf_dictionary,
                                            ctf_type);
  bool type_is_anonymous = (type_name == "");
  uint32_t kind = ctf_type_kind_forwarded (ctf_dictionary, ctf_type);

  if (kind == CTF_K_UNION)
    {
      union_decl_sptr
       union_fwd(new union_decl(ctxt->ir_env,
                                type_name,
                                /*alignment=*/0,
                                location(),
                                decl_base::VISIBILITY_DEFAULT,
                                type_is_anonymous));
      union_fwd->set_is_declaration_only(true);
      result = union_fwd;
    }
  else
    {
      if (!type_is_anonymous)
        if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
          if (result = lookup_class_type(type_name, *corp))
            return is_type(result);

      class_decl_sptr
       struct_fwd(new class_decl(ctxt->ir_env, type_name,
                                 /*alignment=*/0, /*size=*/0,
                                 true /* is_struct */,
                                 location(),
                                 decl_base::VISIBILITY_DEFAULT,
                                 type_is_anonymous));
      struct_fwd->set_is_declaration_only(true);
      result = struct_fwd;
    }

  if (!result)
    return is_type(result);

  add_decl_to_scope(result, tunit->get_global_scope());
  ctxt->add_type(ctf_dictionary, ctf_type, is_type(result));

  return is_type(result);
}

/// Build and return a struct type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the struct type.

static class_decl_sptr
process_ctf_struct_type(read_context *ctxt,
                        corpus_sptr corp,
                        translation_unit_sptr tunit,
                        ctf_dict_t *ctf_dictionary,
                        ctf_id_t ctf_type)
{
  class_decl_sptr result;
  std::string struct_type_name = ctf_type_name_raw(ctf_dictionary,
                                                   ctf_type);
  bool struct_type_is_anonymous = (struct_type_name == "");

  if (!struct_type_is_anonymous)
    if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
      if (result = lookup_class_type(struct_type_name, *corp))
        return result;

  /* The libabigail IR encodes C struct types in `class' IR nodes.  */
  result.reset(new class_decl(ctxt->ir_env,
                              struct_type_name,
                              ctf_type_size(ctf_dictionary, ctf_type) * 8,
                              ctf_type_align(ctf_dictionary, ctf_type) * 8,
                              true /* is_struct */,
                              location(),
                              decl_base::VISIBILITY_DEFAULT,
                              struct_type_is_anonymous));
  if (!result)
    return result;

  /* The C type system indirectly supports loops by the mean of
     pointers to structs or unions.  Since some contained type can
     refer to this struct, we have to make it available in the cache
     at this point even if the members haven't been added to the IR
     node yet.  */
  add_decl_to_scope(result, tunit->get_global_scope());
  ctxt->add_type(ctf_dictionary, ctf_type, result);

  /* Now add the struct members as specified in the CTF type description.
     This is C, so named types can only be defined in the global
     scope.  */
  process_ctf_sou_members(ctxt, corp, tunit, ctf_dictionary, ctf_type,
                          result);

  return result;
}

/// Build and return an union type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the union type.

static union_decl_sptr
process_ctf_union_type(read_context *ctxt,
                       corpus_sptr corp,
                       translation_unit_sptr tunit,
                       ctf_dict_t *ctf_dictionary,
                       ctf_id_t ctf_type)
{
  union_decl_sptr result;
  std::string union_type_name = ctf_type_name_raw(ctf_dictionary,
                                                   ctf_type);
  bool union_type_is_anonymous = (union_type_name == "");

  if (!union_type_is_anonymous)
    if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
      if (result = lookup_union_type(union_type_name, *corp))
        return result;

  /* Create the corresponding libabigail union IR node.  */
  result.reset(new union_decl(ctxt->ir_env,
                                union_type_name,
                                ctf_type_size(ctf_dictionary, ctf_type) * 8,
                                location(),
                                decl_base::VISIBILITY_DEFAULT,
                                union_type_is_anonymous));
  if (!result)
    return result;

  /* The C type system indirectly supports loops by the mean of
     pointers to structs or unions.  Since some contained type can
     refer to this union, we have to make it available in the cache
     at this point even if the members haven't been added to the IR
     node yet.  */
  add_decl_to_scope(result, tunit->get_global_scope());
  ctxt->add_type(ctf_dictionary, ctf_type, result);

  /* Now add the union members as specified in the CTF type description.
     This is C, so named types can only be defined in the global
     scope.  */
  process_ctf_sou_members(ctxt, corp, tunit, ctf_dictionary, ctf_type,
                          result);

  return result;
}

/// Build and return an array type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the array type.

static array_type_def_sptr
process_ctf_array_type(read_context *ctxt,
                       corpus_sptr corp,
                       translation_unit_sptr tunit,
                       ctf_dict_t *ctf_dictionary,
                       ctf_id_t ctf_type)
{
  array_type_def_sptr result;
  ctf_arinfo_t ctf_ainfo;
  bool is_infinite = false;

  /* First, get the information about the CTF array.  */
  if (static_cast<ctf_id_t>(ctf_array_info(ctf_dictionary,
					   ctf_type,
					   &ctf_ainfo)) == CTF_ERR)
    return result;

  ctf_id_t ctf_element_type = ctf_ainfo.ctr_contents;
  ctf_id_t ctf_index_type = ctf_ainfo.ctr_index;
  uint64_t nelems = ctf_ainfo.ctr_nelems;

  /* Make sure the element type is generated.  */
  type_base_sptr element_type = lookup_type(ctxt, corp, tunit,
                                            ctf_dictionary,
                                            ctf_element_type);
  if (!element_type)
    return result;

  /* Ditto for the index type.  */
  type_base_sptr index_type = lookup_type(ctxt, corp, tunit,
                                          ctf_dictionary,
                                          ctf_index_type);
  if (!index_type)
    return result;

  result = dynamic_pointer_cast<array_type_def>(ctxt->lookup_type(ctf_dictionary,
                                                                  ctf_type));
  if (result)
    return result;

  /* The number of elements of the array determines the IR subranges
     type to build.  */
  array_type_def::subranges_type subranges;
  array_type_def::subrange_sptr subrange;
  array_type_def::subrange_type::bound_value lower_bound;
  array_type_def::subrange_type::bound_value upper_bound;

  lower_bound.set_unsigned(0); /* CTF supports C only.  */
  upper_bound.set_unsigned(nelems > 0 ? nelems - 1 : 0U);

  /* for VLAs number of array elements is 0 */
  if (upper_bound.get_unsigned_value() == 0)
    is_infinite = true;

  subrange.reset(new array_type_def::subrange_type(ctxt->ir_env,
                                                   "",
                                                   lower_bound,
                                                   upper_bound,
                                                   index_type,
                                                   location(),
                                                   translation_unit::LANG_C));
  if (!subrange)
    return result;

  subrange->is_infinite(is_infinite);
  add_decl_to_scope(subrange, tunit->get_global_scope());
  canonicalize(subrange);
  subranges.push_back(subrange);

  /* Finally build the IR for the array type and return it.  */
  result.reset(new array_type_def(element_type, subranges, location()));
  if (result)
    {
      decl_base_sptr array_type_decl = get_type_declaration(result);
      add_decl_to_scope(array_type_decl, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Build and return a qualified type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.

static type_base_sptr
process_ctf_qualified_type(read_context *ctxt,
                           corpus_sptr corp,
                           translation_unit_sptr tunit,
                           ctf_dict_t *ctf_dictionary,
                           ctf_id_t ctf_type)
{
  type_base_sptr result;
  int type_kind = ctf_type_kind(ctf_dictionary, ctf_type);
  ctf_id_t ctf_utype = ctf_type_reference(ctf_dictionary, ctf_type);
  type_base_sptr utype = lookup_type(ctxt, corp, tunit,
                                     ctf_dictionary, ctf_utype);
  if (!utype)
    return result;

  result = dynamic_pointer_cast<type_base>(ctxt->lookup_type(ctf_dictionary,
                                                             ctf_type));
  if (result)
    return result;

  qualified_type_def::CV qualifiers = qualified_type_def::CV_NONE;
  if (type_kind == CTF_K_CONST)
    qualifiers |= qualified_type_def::CV_CONST;
  else if (type_kind == CTF_K_VOLATILE)
    qualifiers |= qualified_type_def::CV_VOLATILE;
  else if (type_kind == CTF_K_RESTRICT)
    qualifiers |= qualified_type_def::CV_RESTRICT;
  else
    ABG_ASSERT_NOT_REACHED;

  // qualifiers are not be use in functions
  if (is_function_type(utype))
    return result;

  result.reset(new qualified_type_def(utype, qualifiers, location()));
  if (result)
    {
      decl_base_sptr qualified_type_decl = get_type_declaration(result);
      add_decl_to_scope(qualified_type_decl, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Build and return a pointer type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the pointer type.

static pointer_type_def_sptr
process_ctf_pointer_type(read_context *ctxt,
                         corpus_sptr corp,
                         translation_unit_sptr tunit,
                         ctf_dict_t *ctf_dictionary,
                         ctf_id_t ctf_type)
{
  pointer_type_def_sptr result;
  ctf_id_t ctf_target_type = ctf_type_reference(ctf_dictionary, ctf_type);
  if (ctf_target_type == CTF_ERR)
    return result;

  type_base_sptr target_type = lookup_type(ctxt, corp, tunit,
                                           ctf_dictionary,
                                           ctf_target_type);
  if (!target_type)
    return result;

  result = dynamic_pointer_cast<pointer_type_def>(ctxt->lookup_type(ctf_dictionary,
                                                                    ctf_type));
  if (result)
    return result;

  result.reset(new pointer_type_def(target_type,
                                      ctf_type_size(ctf_dictionary, ctf_type) * 8,
                                      ctf_type_align(ctf_dictionary, ctf_type) * 8,
                                      location()));
  if (result)
    {
      add_decl_to_scope(result, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Build and return an enum type libabigail IR.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// @return a shared pointer to the IR node for the enum type.

static enum_type_decl_sptr
process_ctf_enum_type(read_context *ctxt,
                      translation_unit_sptr tunit,
                      ctf_dict_t *ctf_dictionary,
                      ctf_id_t ctf_type)
{
  enum_type_decl_sptr result;
  std::string enum_name = ctf_type_name_raw(ctf_dictionary, ctf_type);

  if (!enum_name.empty())
    if (corpus_sptr corp = ctxt->should_reuse_type_from_corpus_group())
      if (result = lookup_enum_type(enum_name, *corp))
        return result;

  /* Build a signed integral type for the type of the enumerators, aka
     the underlying type.  The size of the enumerators in bytes is
     specified in the CTF enumeration type.  */
  size_t utype_size_in_bits = ctf_type_size(ctf_dictionary, ctf_type) * 8;
  type_decl_sptr utype;

  utype.reset(new type_decl(ctxt->ir_env,
                              "",
                              utype_size_in_bits,
                              utype_size_in_bits,
                              location()));
  utype->set_is_anonymous(true);
  utype->set_is_artificial(true);
  if (!utype)
    return result;
  add_decl_to_scope(utype, tunit->get_global_scope());
  canonicalize(utype);

  /* Iterate over the enum entries.  */
  enum_type_decl::enumerators enms;
  ctf_next_t *enum_next = NULL;
  const char *ename;
  int evalue;

  while ((ename = ctf_enum_next(ctf_dictionary, ctf_type, &enum_next, &evalue)))
    enms.push_back(enum_type_decl::enumerator(ctxt->ir_env, ename, evalue));
  if (ctf_errno(ctf_dictionary) != ECTF_NEXT_END)
    {
      fprintf(stderr, "ERROR from ctf_enum_next\n");
      return result;
    }

  result.reset(new enum_type_decl(enum_name.c_str(), location(),
                                  utype, enms, enum_name.c_str()));
  if (result)
    {
      add_decl_to_scope(result, tunit->get_global_scope());
      ctxt->add_type(ctf_dictionary, ctf_type, result);
    }

  return result;
}

/// Add a new type declaration to the given libabigail IR corpus CORP.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the source type.
///
/// Note that if @ref ctf_type can't reliably be translated to the IR
/// then it is simply ignored.
///
/// @return a shared pointer to the IR node for the type.

static type_base_sptr
process_ctf_type(read_context *ctxt,
                 corpus_sptr corp,
                 translation_unit_sptr tunit,
                 ctf_dict_t *ctf_dictionary,
                 ctf_id_t ctf_type)
{
  int type_kind = ctf_type_kind(ctf_dictionary, ctf_type);
  type_base_sptr result;

  if (ctxt->lookup_unknown_type(ctf_type))
    return nullptr;

  if ((result = ctxt->lookup_type(ctf_dictionary, ctf_type)))
    return result;

  switch (type_kind)
    {
    case CTF_K_INTEGER:
    case CTF_K_FLOAT:
      {
        type_decl_sptr type_decl
          = process_ctf_base_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = is_type(type_decl);
        break;
      }
    case CTF_K_TYPEDEF:
      {
        typedef_decl_sptr typedef_decl
          = process_ctf_typedef(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = is_type(typedef_decl);
        break;
      }
    case CTF_K_POINTER:
      {
        pointer_type_def_sptr pointer_type
          = process_ctf_pointer_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = pointer_type;
        break;
      }
    case CTF_K_CONST:
    case CTF_K_VOLATILE:
    case CTF_K_RESTRICT:
      {
        type_base_sptr qualified_type
          = process_ctf_qualified_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = qualified_type;
        break;
      }
    case CTF_K_ARRAY:
      {
        array_type_def_sptr array_type
          = process_ctf_array_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = array_type;
        break;
      }
    case CTF_K_ENUM:
      {
        enum_type_decl_sptr enum_type
          = process_ctf_enum_type(ctxt, tunit, ctf_dictionary, ctf_type);
        result = enum_type;
        break;
      }
    case CTF_K_FUNCTION:
      {
        function_type_sptr function_type
          = process_ctf_function_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = function_type;
        break;
      }
    case CTF_K_STRUCT:
      {
        class_decl_sptr struct_decl
          = process_ctf_struct_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = is_type(struct_decl);
        break;
      }
    case CTF_K_FORWARD:
      {
        result = process_ctf_forward_type(ctxt, tunit,
					  ctf_dictionary,
                                          ctf_type);
      }
      break;
    case CTF_K_UNION:
      {
        union_decl_sptr union_decl
          = process_ctf_union_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
        result = is_type(union_decl);
        break;
      }
    case CTF_K_UNKNOWN:
      /* Unknown types are simply ignored.  */
    default:
      break;
    }

  if (!result)
    {
      fprintf(stderr, "NOT PROCESSED TYPE %lu\n", ctf_type);
      ctxt->add_unknown_type(ctf_type);
    }

  return result;
}

/// Given a CTF type id, lookup the corresponding libabigail IR type.
/// If the IR type hasn't been generated yet, generate it.
///
/// @param ctxt the read context.
/// @param corp the libabigail IR corpus being constructed.
/// @param tunit the current IR translation unit.
/// @param ctf_dictionary the CTF dictionary being read.
/// @param ctf_type the CTF type ID of the looked type.
///
/// Note that if @ref ctf_type can't reliably be translated to the IR
/// then a NULL shared pointer is returned.
///
/// @return a shared pointer to the IR node for the type.

static type_base_sptr
lookup_type(read_context *ctxt, corpus_sptr corp,
            translation_unit_sptr tunit, ctf_dict_t *ctf_dictionary,
            ctf_id_t ctf_type)
{
  type_base_sptr result = ctxt->lookup_type(ctf_dictionary, ctf_type);

  if (!result)
    result = process_ctf_type(ctxt, corp, tunit, ctf_dictionary, ctf_type);
  return result;
}

/// Process a CTF archive and create libabigail IR for the types,
/// variables and function declarations found in the archive, iterating
/// over public symbols.  The IR is added to the given corpus.
///
/// @param ctxt the read context containing the CTF archive to
/// process.
/// @param corp the IR corpus to which add the new contents.

static void
process_ctf_archive(read_context *ctxt, corpus_sptr corp)
{
  /* We only have a translation unit.  */
  translation_unit_sptr ir_translation_unit =
    std::make_shared<translation_unit>(ctxt->ir_env, "", 64);
  ir_translation_unit->set_language(translation_unit::LANG_C);
  corp->add(ir_translation_unit);

  int ctf_err;
  ctf_dict_t *ctf_dict;
  const auto symtab = ctxt->symtab;
  symtab_reader::symtab_filter filter = symtab->make_filter();
  filter.set_public_symbols();
  std::string dict_name;

  if (corp->get_origin() & corpus::LINUX_KERNEL_BINARY_ORIGIN)
    {
      tools_utils::base_name(ctxt->filename, dict_name);

      if (dict_name != "vmlinux")
        // remove .ko suffix
        dict_name.erase(dict_name.length() - 3, 3);

      std::replace(dict_name.begin(), dict_name.end(), '-', '_');
    }

  if ((ctf_dict = ctf_dict_open(ctxt->ctfa,
                                dict_name.empty() ? NULL : dict_name.c_str(),
                                &ctf_err)) == NULL)
    {
      fprintf(stderr, "ERROR dictionary not found\n");
      abort();
    }

  for (const auto& symbol : symtab_reader::filtered_symtab(*symtab, filter))
    {
      std::string sym_name = symbol->get_name();
      ctf_id_t ctf_sym_type;

      ctf_sym_type = ctf_lookup_variable(ctf_dict, sym_name.c_str());
      if (ctf_sym_type == (ctf_id_t) -1
          && !(corp->get_origin() & corpus::LINUX_KERNEL_BINARY_ORIGIN))
        // lookup in function objects
        ctf_sym_type = ctf_lookup_by_symbol_name(ctf_dict, sym_name.c_str());

      if (ctf_sym_type == (ctf_id_t) -1)
        continue;

      if (ctf_type_kind(ctf_dict, ctf_sym_type) != CTF_K_FUNCTION)
        {
          const char *var_name = sym_name.c_str();
          type_base_sptr var_type = lookup_type(ctxt, corp, ir_translation_unit,
                                                ctf_dict, ctf_sym_type);
          if (!var_type)
            /* Ignore variable if its type can't be sorted out.  */
            continue;

          var_decl_sptr var_declaration;
          var_declaration.reset(new var_decl(var_name,
                                             var_type,
                                             location(),
                                             var_name));

          var_declaration->set_symbol(symbol);
          add_decl_to_scope(var_declaration,
                            ir_translation_unit->get_global_scope());
          var_declaration->set_is_in_public_symbol_table(true);
          ctxt->maybe_add_var_to_exported_decls(var_declaration.get());
        }
      else
        {
          const char *func_name = sym_name.c_str();
          ctf_id_t ctf_sym = ctf_sym_type;
          type_base_sptr func_type = lookup_type(ctxt, corp, ir_translation_unit,
                                                 ctf_dict, ctf_sym);
          if (!func_type)
            /* Ignore function if its type can't be sorted out.  */
            continue;

          function_decl_sptr func_declaration;
          func_declaration.reset(new function_decl(func_name,
                                                   func_type,
                                                   0 /* is_inline */,
                                                   location()));

          func_declaration->set_symbol(symbol);
          add_decl_to_scope(func_declaration,
                            ir_translation_unit->get_global_scope());
          func_declaration->set_is_in_public_symbol_table(true);
          ctxt->maybe_add_fn_to_exported_decls(func_declaration.get());
        }
    }

  ctf_dict_close(ctf_dict);
  /* Canonicalize all the types generated above.  This must be
     done "a posteriori" because the processing of types may
     require other related types to not be already
     canonicalized.  */
  ctxt->canonicalize_all_types();
}

/// Open the ELF file described by the given read context.
///
/// @param ctxt the read context.
/// @return 0 if the ELF file can't be opened.
/// @return 1 otherwise.

static int
open_elf_handler(read_context *ctxt)
{
  /* libelf requires to negotiate/set the version of ELF.  */
  if (elf_version(EV_CURRENT) == EV_NONE)
    return 0;

  /* Open an ELF handler.  */
  ctxt->elf_fd = open(ctxt->filename.c_str(), O_RDONLY);
  if (ctxt->elf_fd == -1)
    return 0;

  ctxt->elf_handler = elf_begin(ctxt->elf_fd, ELF_C_READ, NULL);
  if (ctxt->elf_handler == NULL)
    {
      fprintf(stderr, "cannot open %s: %s\n",
               ctxt->filename.c_str(), elf_errmsg(elf_errno()));
      close(ctxt->elf_fd);
      return 0;
    }

  return 1;
}

/// Close the ELF file described by the given read context.
///
/// @param ctxt the read context.

static void
close_elf_handler (read_context *ctxt)
{
  /* Finish the ELF handler and close the associated file.  */
  elf_end(ctxt->elf_handler);
  close(ctxt->elf_fd);

  /* Finish the ELF handler and close the associated debug file.  */
  elf_end(ctxt->elf_handler_dbg);
  close(ctxt->elf_fd_dbg);
}

/// Fill a CTF section description with the information in a given ELF
/// section.
///
/// @param elf_section the ELF section from which to get.
/// @param ctf_section the CTF section to fill with the raw data.

static void
fill_ctf_section(Elf_Scn *elf_section, ctf_sect_t *ctf_section)
{
  GElf_Shdr section_header_mem, *section_header;
  Elf_Data *section_data;

  section_header = gelf_getshdr(elf_section, &section_header_mem);
  section_data = elf_getdata(elf_section, 0);

  ABG_ASSERT (section_header != NULL);
  ABG_ASSERT (section_data != NULL);

  ctf_section->cts_name = ""; /* This is not actually used by libctf.  */
  ctf_section->cts_data = (char *) section_data->d_buf;
  ctf_section->cts_size = section_data->d_size;
  ctf_section->cts_entsize = section_header->sh_entsize;
}

/// Find a CTF section and debug symbols in a given ELF using
/// .gnu_debuglink section.
///
/// @param ctxt the read context.
/// @param ctf_dbg_section the CTF section to fill with the raw data.
static void
find_alt_debuginfo(read_context *ctxt, Elf_Scn **ctf_dbg_scn)
{
  std::string name;
  Elf_Data *data;

  Elf_Scn *section = elf_helpers::find_section
    (ctxt->elf_handler, ".gnu_debuglink", SHT_PROGBITS);

  if (section
      && (data = elf_getdata(section, NULL))
      && data->d_size != 0)
    name = (char *) data->d_buf;

  int fd = -1;
  Elf *hdlr = NULL;
  *ctf_dbg_scn = NULL;

  if (!name.empty())
    for (vector<char**>::const_iterator i = ctxt->debug_info_root_paths_.begin();
         i != ctxt->debug_info_root_paths_.end();
         ++i)
      {
        std::string file_path;
        if (!tools_utils::find_file_under_dir(**i, name, file_path))
          continue;

        if ((fd = open(file_path.c_str(), O_RDONLY)) == -1)
          continue;

        if ((hdlr = elf_begin(fd, ELF_C_READ, NULL)) == NULL)
          {
            close(fd);
            continue;
          }

        ctxt->symtab =
          symtab_reader::symtab::load(hdlr, ctxt->ir_env, nullptr);

        // unlikely .ctf was designed to be present in stripped file
        *ctf_dbg_scn =
          elf_helpers::find_section(hdlr, ".ctf", SHT_PROGBITS);
          break;

        elf_end(hdlr);
        close(fd);
      }

  // If we don't have a symbol table, use current one in ELF file
  if (!ctxt->symtab)
    ctxt->symtab =
     symtab_reader::symtab::load(ctxt->elf_handler, ctxt->ir_env, nullptr);

  ctxt->elf_handler_dbg = hdlr;
  ctxt->elf_fd_dbg = fd;
}

/// Slurp certain information from the ELF file described by a given
/// read context and install it in a libabigail corpus.
///
/// @param ctxt the read context
/// @param corp the libabigail corpus in which to install the info.
/// @param status the resulting status flags.
static void
slurp_elf_info(read_context *ctxt,
               corpus_sptr corp,
               elf_reader::status& status)
{
  /* Set the ELF architecture.  */
  GElf_Ehdr *ehdr, eh_mem;
  Elf_Scn *symtab_scn;
  Elf_Scn *ctf_scn, *ctf_dbg_scn;
  Elf_Scn *strtab_scn;

  if (!(ehdr = gelf_getehdr(ctxt->elf_handler, &eh_mem)))
      return;

  corp->set_architecture_name(elf_helpers::e_machine_to_string(ehdr->e_machine));

  find_alt_debuginfo(ctxt, &ctf_dbg_scn);
  ABG_ASSERT(ctxt->symtab);
  corp->set_symtab(ctxt->symtab);

  if (corp->get_origin() & corpus::LINUX_KERNEL_BINARY_ORIGIN)
    {
      status |= elf_reader::STATUS_OK;
      return;
    }

  /* Get the raw ELF section contents for libctf.  */
  const char *ctf_name = ".ctf";
  ctf_scn = elf_helpers::find_section_by_name(ctxt->elf_handler, ctf_name);
  if (ctf_scn == NULL)
    {
      if (ctf_dbg_scn)
        ctf_scn = ctf_dbg_scn;
      else
        {
          status |= elf_reader::STATUS_DEBUG_INFO_NOT_FOUND;
          return;
        }
    }

  // ET_{EXEC,DYN} needs .dyn{sym,str} in ctf_arc_bufopen
  const char *symtab_name = ".dynsym";
  const char *strtab_name = ".dynstr";

  if (ehdr->e_type == ET_REL)
    {
      symtab_name = ".symtab";
      strtab_name = ".strtab";
    }

  symtab_scn = elf_helpers::find_section_by_name(ctxt->elf_handler, symtab_name);
  strtab_scn = elf_helpers::find_section_by_name(ctxt->elf_handler, strtab_name);
  if (symtab_scn == NULL || strtab_scn == NULL)
    {
      status |= elf_reader::STATUS_NO_SYMBOLS_FOUND;
      return;
    }

  fill_ctf_section(ctf_scn, &ctxt->ctf_sect);
  fill_ctf_section(symtab_scn, &ctxt->symtab_sect);
  fill_ctf_section(strtab_scn, &ctxt->strtab_sect);

  status |= elf_reader::STATUS_OK;
}

/// Create and return a new read context to process CTF information
/// from a given ELF file.
///
/// @param elf_path the patch of some ELF file.
/// @param env a libabigail IR environment.

read_context_sptr
create_read_context(const std::string& elf_path,
                    const vector<char**>& debug_info_root_paths,
                    ir::environment *env)
{
  read_context_sptr result(new read_context(elf_path,
                                            debug_info_root_paths,
                                            env));
  return result;
}

/// Read the CTF information from some source described by a given
/// read context and process it to create a libabigail IR corpus.
/// Store the corpus in the same read context.
///
/// @param ctxt the read context to use.
///
/// @param status the resulting status of the corpus read.
///
/// @return a shared pointer to the read corpus.

corpus_sptr
read_corpus(read_context *ctxt, elf_reader::status &status)
{
  corpus_sptr corp
    = std::make_shared<corpus>(ctxt->ir_env, ctxt->filename);
  ctxt->cur_corpus_ = corp;
  status = elf_reader::STATUS_UNKNOWN;

  /* Open the ELF file.  */
  if (!open_elf_handler(ctxt))
      return corp;

  bool is_linux_kernel = elf_helpers::is_linux_kernel(ctxt->elf_handler);
  corpus::origin origin = corpus::CTF_ORIGIN;

  if (is_linux_kernel)
    origin |= corpus::LINUX_KERNEL_BINARY_ORIGIN;
  corp->set_origin(origin);

  if (ctxt->cur_corpus_group_)
    ctxt->cur_corpus_group_->add_corpus(ctxt->cur_corpus_);

  slurp_elf_info(ctxt, corp, status);
  if (!is_linux_kernel
      && ((status & elf_reader::STATUS_DEBUG_INFO_NOT_FOUND) |
          (status & elf_reader::STATUS_NO_SYMBOLS_FOUND)))
      return corp;

  // Set the set of exported declaration that are defined.
  ctxt->exported_decls_builder
   (ctxt->cur_corpus_->get_exported_decls_builder().get());

  int errp;
  if (corp->get_origin() & corpus::LINUX_KERNEL_BINARY_ORIGIN)
    {
      std::string filename;
      if (tools_utils::base_name(ctxt->filename, filename)
          && filename == "vmlinux")
        {
          std::string vmlinux_ctfa_path = ctxt->filename + ".ctfa";
          ctxt->ctfa = ctf_arc_open(vmlinux_ctfa_path.c_str(), &errp);
        }
    }
  else
    /* Build the ctfa from the contents of the relevant ELF sections,
       and process the CTF archive in the read context, if any.
       Information about the types, variables, functions, etc contained
       in the archive are added to the given corpus.  */
    ctxt->ctfa = ctf_arc_bufopen(&ctxt->ctf_sect, &ctxt->symtab_sect,
                                 &ctxt->strtab_sect, &errp);

  ctxt->ir_env->canonicalization_is_done(false);
  if (ctxt->ctfa == NULL)
    status |= elf_reader::STATUS_DEBUG_INFO_NOT_FOUND;
  else
    {
      process_ctf_archive(ctxt, corp);
      ctxt->cur_corpus_->sort_functions();
      ctxt->cur_corpus_->sort_variables();
    }

  ctxt->ir_env->canonicalization_is_done(true);

  /* Cleanup and return.  */
  close_elf_handler(ctxt);
  return corp;
}

/// Read the CTF information from some source described by a given
/// read context and process it to create a libabigail IR corpus.
/// Store the corpus in the same read context.
///
/// @param ctxt the read context to use.
///
/// @param status the resulting status of the corpus read.
///
/// @return a shared pointer to the read corpus.

corpus_sptr
read_corpus(const read_context_sptr &ctxt, elf_reader::status &status)
{return read_corpus(ctxt.get(), status);}

/// Set the @ref corpus_group being created to the current read context.
///
/// @param ctxt the read_context to consider.
///
/// @param group the @ref corpus_group to set.
void
set_read_context_corpus_group(read_context& ctxt,
                              corpus_group_sptr& group)
{
  ctxt.cur_corpus_group_ = group;
}

/// Read a corpus and add it to a given @ref corpus_group.
///
/// @param ctxt the reading context to consider.
///
/// @param group the @ref corpus_group to add the new corpus to.
///
/// @param status output parameter. The status of the read.  It is set
/// by this function upon its completion.
corpus_sptr
read_and_add_corpus_to_group_from_elf(read_context* ctxt,
                                      corpus_group& group,
                                      elf_reader::status& status)
{
  corpus_sptr result;
  corpus_sptr corp = read_corpus(ctxt, status);
  if (status & elf_reader::STATUS_OK)
    {
      if (!corp->get_group())
        group.add_corpus(corp);
      result = corp;
    }

  return result;
}

/// Re-initialize a read_context so that it can re-used to read
/// another binary.
///
/// @param ctxt the context to re-initialize.
///
/// @param elf_path the path to the elf file the context is to be used
/// for.
///
/// @param environment the environment used by the current context.
/// This environment contains resources needed by the reader and by
/// the types and declarations that are to be created later.  Note
/// that ABI artifacts that are to be compared all need to be created
/// within the same environment.
///
/// Please also note that the life time of this environment object
/// must be greater than the life time of the resulting @ref
/// read_context the context uses resources that are allocated in the
/// environment.
void
reset_read_context(read_context_sptr	&ctxt,
                   const std::string&	 elf_path,
                   const vector<char**>& debug_info_root_path,
                   ir::environment*	 environment)
{
  if (ctxt)
    ctxt->initialize(elf_path, debug_info_root_path, environment);
}

/// Returns a key to be use in types_map dict conformed by
/// dictionary id and the CTF type id for a given type.
///
/// CTF id types are unique by child dictionary, but CTF id
/// types in parent dictionary are unique across the all
/// dictionaries in the CTF archive, to differentiate
/// one each other this member function relies in
/// ctf_type_isparent function.
///
/// @param dic the pointer to CTF dictionary where the @p type
/// was found.
///
/// @param type the id for given CTF type.
std::string
dic_type_key(ctf_dict_t *dic, ctf_id_t ctf_type)
{
  std::stringstream key;

  if (ctf_type_isparent (dic, ctf_type))
    key << std::hex << ctf_type;
  else
    key << std::hex << ctf_type << '-' << ctf_cuname(dic);
  return key.str();
}

} // End of namespace ctf_reader
} // End of namespace abigail
