// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
// -*- Mode: C++ -*-
//
// Copyright (C) 2013-2021 Red Hat, Inc.
//
// Author: Dodji Seketeli

/// @file
///
/// This is a program aimed at checking that a binary instrumentation
/// (bi) file is well formed and valid enough.  It acts by loading an
/// input bi file and saving it back to a temporary file.  It then
/// runs a diff on the two files and expects the result of the diff to
/// be empty.

#include "config.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>
#include "abg-config.h"
#include "abg-tools-utils.h"
#include "abg-ir.h"
#include "abg-corpus.h"
#include "abg-reader.h"
#include "abg-dwarf-reader.h"
#ifdef WITH_CTF
#include "abg-ctf-reader.h"
#endif
#include "abg-writer.h"
#include "abg-suppression.h"

using std::string;
using std::cerr;
using std::cin;
using std::cout;
using std::ostream;
using std::ofstream;
using std::vector;
using std::unordered_set;
using std::unique_ptr;
using abigail::tools_utils::emit_prefix;
using abigail::tools_utils::check_file;
using abigail::tools_utils::file_type;
using abigail::tools_utils::guess_file_type;
using abigail::suppr::suppression_sptr;
using abigail::suppr::suppressions_type;
using abigail::suppr::read_suppressions;
using abigail::type_base;
using abigail::type_or_decl_base;
using abigail::type_base_sptr;
using abigail::type_or_decl_base_sptr;
using abigail::corpus;
using abigail::corpus_sptr;
using abigail::xml_reader::read_translation_unit_from_file;
using abigail::xml_reader::read_translation_unit_from_istream;
using abigail::xml_reader::read_corpus_from_native_xml;
using abigail::xml_reader::read_corpus_from_native_xml_file;
using abigail::xml_reader::read_corpus_group_from_input;
#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
using abigail::xml_reader::get_types_from_type_id;
using abigail::xml_reader::get_artifact_used_by_relation_map;
#endif
using abigail::dwarf_reader::read_corpus_from_elf;
using abigail::xml_writer::write_translation_unit;
using abigail::xml_writer::write_context_sptr;
using abigail::xml_writer::create_write_context;
using abigail::xml_writer::write_corpus;
using abigail::xml_writer::write_corpus_to_archive;

struct options
{
  string			wrong_option;
  string			file_path;
  bool				display_version;
  bool				read_from_stdin;
  bool				read_tu;
  bool				diff;
  bool				noout;
#ifdef WITH_CTF
  bool				use_ctf;
#endif
  std::shared_ptr<char>	di_root_path;
  vector<string>		suppression_paths;
  string			headers_dir;
  vector<string>		header_files;
#if WITH_SHOW_TYPE_USE_IN_ABILINT
  string			type_id_to_show;
#endif

  options()
    : display_version(false),
      read_from_stdin(false),
      read_tu(false),
      diff(false),
      noout(false)
#ifdef WITH_CTF
    ,
      use_ctf(false)
#endif
  {}
};//end struct options;

#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
/// A tree node representing the "use" relation between an artifact A
/// (e.g, a type) and a set of artifacts {A'} that use "A" as in "A"
/// is a sub-type of A'.
///
/// So the node contains the artifact A and a vector children nodes
/// that contain the A' artifacts that use A.
struct artifact_use_relation_tree
{
  artifact_use_relation_tree *root_node = nullptr;
  /// The parent node of this one.  Is nullptr if this node is the root
  /// node.
  artifact_use_relation_tree *parent = nullptr;
  /// The artifact contained in this node.
  type_or_decl_base* artifact = nullptr;
  /// The vector of children nodes that carry the artifacts that
  /// actually use the 'artifact' above.  In other words, the
  /// 'artifact" data member above is a sub-type of each artifact
  /// contained in this vector.
  vector<unique_ptr<artifact_use_relation_tree>> artifact_users;
  /// This is the set of artifacts that have been added to the tree.
  /// This is useful to ensure that all artifacts are added just once
  /// in the tree to prevent infinite loops.
  unordered_set<type_or_decl_base *> artifacts;

  /// The constructor of the tree node.
  ///
  /// @param the artifact to consider.
  artifact_use_relation_tree(type_or_decl_base* t)
    : artifact (t)
  {
    ABG_ASSERT(t && !artifact_in_tree(t));
    record_artifact(t);
  }

  /// Add a user artifact node for the artifact carried by this node.
  ///
  /// The artifact carried by the current node is a sub-type of the
  /// artifact carried by the 'user' node being added.
  ///
  /// @param user a tree node that carries an artifact that uses the
  /// artifact carried by the current node.
  void
  add_artifact_user(artifact_use_relation_tree *user)
  {
    ABG_ASSERT(user && !artifact_in_tree(user->artifact ));
    artifact_users.push_back(unique_ptr<artifact_use_relation_tree>(user));
    user->parent = this;
    record_artifact(user->artifact);
  }

  /// Move constructor.
  ///
  /// @param o the source of the move.
  artifact_use_relation_tree(artifact_use_relation_tree &&o)
  {
    parent = o.parent;
    artifact = o.artifact;
    artifact_users = std::move(o.artifact_users);
    artifacts = std::move(o.artifacts);
  }

  /// Move assignment operator.
  ///
  /// @param o the source of the assignment.
  artifact_use_relation_tree& operator=(artifact_use_relation_tree&& o)
  {
    parent = o.parent;
    artifact = o.artifact;
    artifact_users = std::move(o.artifact_users);
    artifacts = std::move(o.artifacts);
    return *this;
  }

  /// Test if the current node is a leaf node.
  ///
  /// @return true if the artifact carried by the current node has no
  /// user artifacts.
  bool
  is_leaf() const
  {return artifact_users.empty();}

  /// Test if the current node is a root node.
  ///
  /// @return true if the current artifact uses no other artifact.
  bool
  is_root() const
  {return parent == nullptr;}

  /// Test wether a given artifact has been added to the tree.
  ///
  /// Here, the tree means the tree that the current tree node is part
  /// of.
  ///
  /// An artifact is considered as having been added to the tree if
  /// artifact_use_relation_tree::record_artifact has been invoked on
  /// it.
  ///
  /// @param artifact the artifact to consider.
  ///
  /// @return true iff @p artifact is present in the tree.
  bool
  artifact_in_tree(type_or_decl_base *artifact)
  {
    artifact_use_relation_tree *root_node = get_root_node();
    ABG_ASSERT(root_node);
    return root_node->artifacts.find(artifact) != root_node->artifacts.end();
  }

  /// Record an artifact as being added to the current tree.
  ///
  /// Note that this function assumes the artifact is not already
  /// present in the tree containing the current tree node.
  ///
  /// @param artifact the artifact to consider.
  void
  record_artifact(type_or_decl_base *artifact)
  {
    ABG_ASSERT(!artifact_in_tree(artifact));
    artifact_use_relation_tree *root_node = get_root_node();
    ABG_ASSERT(root_node);
    root_node->artifacts.insert(artifact);
  }

  /// Get the root node of the current tree.
  ///
  /// @return the root node of the current tree.
  artifact_use_relation_tree*
  get_root_node()
  {
    if (root_node)
      return root_node;

    if (parent == nullptr)
      return this;

    root_node = parent->get_root_node();
    return root_node;
  }

  artifact_use_relation_tree(const artifact_use_relation_tree&) = delete;
  artifact_use_relation_tree& operator=(const artifact_use_relation_tree&) = delete;
}; // end struct artifact_use_relation_tree

/// Fill an "artifact use" tree from a map that associates a type T
/// (or artifact) to artifacts that use T as a sub-type.
///
/// @param artifact_use_rel the map that establishes the relation
/// between a type T and the artifacts that use T as a sub-type.
///
/// @parm tree output parameter.  This function will fill up this tree
/// from the information carried in @p artifact_use_rel.  Each node of
/// the tree contains an artifact A and its children nodes contain the
/// artifacts A' that use A as a sub-type.
static void
fill_artifact_use_tree(const std::unordered_map<type_or_decl_base*,
						vector<type_or_decl_base*>>& artifact_use_rel,
		       artifact_use_relation_tree& tree)
{
  auto r = artifact_use_rel.find(tree.artifact);
  if (r == artifact_use_rel.end())
    return;

  // Walk the users of "artifact", create a tree node for each one of
  // them, and add them as children node of the current tree node
  // named 'tree'.
  for (auto user : r->second)
    {
      if (tree.artifact_in_tree(user))
	// The artifact has already been added to the tree, so skip it
	// otherwise we can loop for ever.
	continue;

      artifact_use_relation_tree *user_tree =
	new artifact_use_relation_tree(user);

      // Now add the new user node as a child of the current tree
      // node.
      tree.add_artifact_user(user_tree);

      // Recursively fill the newly created tree node.
      fill_artifact_use_tree(artifact_use_rel, *user_tree);
    }
}

/// construct an "artifact use tree" for a type designated by a "type-id".
/// (or artifact) to artifacts that use T as a sub-type.
///
/// Each node of the "artifact use tree" contains a type T and its
/// children nodes contain the artifacts A' that use T as a sub-type.
/// The root node is the type designed by a given type-id.
///
/// @param ctxt the abixml read context to consider.
///
/// @param type_id the type-id of the type to construct the "use tree"
/// for.
static unique_ptr<artifact_use_relation_tree>
build_type_use_tree(abigail::xml_reader::read_context &ctxt,
		    const string& type_id)
{
  unique_ptr<artifact_use_relation_tree> result;
  vector<type_base_sptr>* types = get_types_from_type_id(ctxt, type_id);
  if (!types)
    return result;

  std::unordered_map<type_or_decl_base*, vector<type_or_decl_base*>>*
    artifact_use_rel = get_artifact_used_by_relation_map(ctxt);
  if (!artifact_use_rel)
    return result;

  type_or_decl_base_sptr type = types->front();
  unique_ptr<artifact_use_relation_tree> use_tree
    (new artifact_use_relation_tree(type.get()));

  fill_artifact_use_tree(*artifact_use_rel, *use_tree);

  result = std::move(use_tree);
  return result;
}

/// Emit a visual representation of a "type use trace".
///
/// The trace is vector of strings.  Each string is the textual
/// representation of a type.  The next element in the vector is a
/// type using the previous element, as in, the "previous element is a
/// sub-type of the next element".
///
/// This is a sub-routine of emit_artifact_use_trace.
///
/// @param the trace vector to emit.
///
/// @param out the output stream to emit the trace to.
static void
emit_trace(const vector<string>& trace, ostream& out)
{
  if (trace.empty())
    return;

  if (!trace.empty())
    // Make the beginning of the trace line of the usage of a given
    // type be easily recognizeable by a "pattern".
    out << "===";

  for (auto element : trace)
    out << "-> " << element << " ";

  if (!trace.empty())
    // Make the end of the trace line of the usage of a given type be
    // easily recognizeable by another "pattern".
    out << " <-~~~";

  out << "\n";
}

/// Walk a @ref artifact_use_relation_tree to emit a "type-is-used-by"
/// trace.
///
/// The tree carries the information about how a given type is used by
/// other types.  This function walks the tree by visiting a node
/// carrying a given type T, and then the nodes for which T is a
/// sub-type.  The function accumulates a trace made of the textual
/// representation of the visited nodes and then emits that trace on
/// an output stream.
///
/// @param artifact_use_tree the tree to walk.
///
/// @param trace the accumulated vector of the textual representations
/// of the types carried by the visited nodes.
///
/// @param out the output stream to emit the trace to.
static void
emit_artifact_use_trace(const artifact_use_relation_tree& artifact_use_tree,
			vector<string>& trace, ostream& out)
{
  type_or_decl_base* artifact = artifact_use_tree.artifact;
  if (!artifact)
    return;

  string repr = artifact->get_pretty_representation();
  trace.push_back(repr);

  if (artifact_use_tree.artifact_users.empty())
    {
      // We reached a leaf node.  This means that no other artifact
      // uses the artifact carried by this leaf node.  So, we want to
      // emit the trace accumulated to this point.

      // But we only want to emit the usage traces that end up with a
      // function of variable that have an associated ELF symbol.
      bool do_emit_trace = false;
      if (is_decl(artifact))
	{
	  if (abigail::ir::var_decl* v = is_var_decl(artifact))
	    if (v->get_symbol()
		|| is_at_global_scope(v)
		|| !v->get_linkage_name().empty())
	      do_emit_trace = true;
	  if (abigail::ir::function_decl* f = is_function_decl(artifact))
	    if (f->get_symbol()
		|| is_at_global_scope(f)
		|| !f->get_linkage_name().empty())
	      do_emit_trace = true;
	}

      // OK now, really emit the trace.
      if (do_emit_trace)
	emit_trace(trace, out);

      trace.pop_back();
      return;
    }

  for (const auto &user : artifact_use_tree.artifact_users)
    emit_artifact_use_trace(*user, trace, out);

  trace.pop_back();
}

/// Walk a @ref artifact_use_relation_tree to emit a "type-is-used-by"
/// trace.
///
/// The tree carries the information about how a given type is used by
/// other types.  This function walks the tree by visiting a node
/// carrying a given type T, and then the nodes for which T is a
/// sub-type.  The function then emits a trace of how the root type is
/// used.
///
/// @param artifact_use_tree the tree to walk.
///
/// @param out the output stream to emit the trace to.
static void
emit_artifact_use_trace(const artifact_use_relation_tree& artifact_use_tree,
			ostream& out)
{
  vector<string> trace;
  emit_artifact_use_trace(artifact_use_tree, trace, out);
}

/// Show how a type is used.
///
/// The type to consider is designated by a type-id string that is
/// carried by the options data structure.
///
/// @param ctxt the abixml read context to consider.
///
/// @param the type_id of the type which usage to analyse.
static bool
show_how_type_is_used(abigail::xml_reader::read_context &ctxt,
		      const string& type_id)
{
  if (type_id.empty())
    return false;

  unique_ptr<artifact_use_relation_tree> use_tree =
    build_type_use_tree(ctxt, type_id);
  if (!use_tree)
    return false;

  // Now walk the use_tree to emit the type use trace
  if (use_tree->artifact)
    {
      std::cout << "Type ID '"
		<< type_id << "' is for type '"
		<< use_tree->artifact->get_pretty_representation()
		<< "'\n"
		<< "The usage graph for that type is:\n";
      emit_artifact_use_trace(*use_tree, std::cout);
    }
  return true;
}
#endif // WITH_SHOW_TYPE_USE_IN_ABILINT

static void
display_usage(const string& prog_name, ostream& out)
{
  emit_prefix(prog_name, out)
    << "usage: " << prog_name << " [options] [<abi-file1>]\n"
    << " where options can be:\n"
    << "  --help  display this message\n"
    << "  --version|-v  display program version information and exit\n"
    << "  --debug-info-dir <path> the path under which to look for "
    "debug info for the elf <abi-file>\n"
    << "  --headers-dir|--hd <path> the path to headers of the elf file\n"
    << "  --header-file|--hf <path> the path to one header of the elf file\n"
    << "  --suppressions|--suppr <path> specify a suppression file\n"
    << "  --diff  for xml inputs, perform a text diff between "
    "the input and the memory model saved back to disk\n"
    << "  --noout  do not display anything on stdout\n"
    << "  --stdin  read abi-file content from stdin\n"
    << "  --tu  expect a single translation unit file\n"
#ifdef WITH_CTF
    << "  --ctf use CTF instead of DWARF in ELF files\n"
#endif
#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
    << "  --show-type-use <type-id>  show how a type is used from the abixml file\n"
#endif
    ;
}

bool
parse_command_line(int argc, char* argv[], options& opts)
{
  if (argc < 2)
    {
      opts.read_from_stdin = true;
      return true;
    }

    for (int i = 1; i < argc; ++i)
      {
	if (argv[i][0] != '-')
	  {
	    if (opts.file_path.empty())
	      opts.file_path = argv[i];
	    else
	      return false;
	  }
	else if (!strcmp(argv[i], "--help"))
	  return false;
	else if (!strcmp(argv[i], "--version")
		 || !strcmp(argv[i], "-v"))
	  {
	    opts.display_version = true;
	    return true;
	  }
	else if (!strcmp(argv[i], "--debug-info-dir"))
	  {
	    if (argc <= i + 1
		|| argv[i + 1][0] == '-')
	      return false;
	    // elfutils wants the root path to the debug info to be
	    // absolute.
	    opts.di_root_path =
	      abigail::tools_utils::make_path_absolute(argv[i + 1]);
	    ++i;
	  }
      else if (!strcmp(argv[i], "--headers-dir")
	       || !strcmp(argv[i], "--hd"))
	{
	  int j = i + 1;
	  if (j >= argc)
	    return false;
	  opts.headers_dir = argv[j];
	  ++i;
	}
      else if (!strcmp(argv[i], "--header-file")
	       || !strcmp(argv[i], "--hf"))
	{
	  int j = i + 1;
	  if (j >= argc)
	    return false;
	  opts.header_files.push_back(argv[j]);
	  ++i;
	}
      else if (!strcmp(argv[i], "--suppressions")
	       || !strcmp(argv[i], "--suppr"))
	{
	  int j = i + 1;
	  if (j >= argc)
	    {
	      opts.wrong_option = argv[i];
	      return true;
	    }
	  opts.suppression_paths.push_back(argv[j]);
	  ++i;
	}
	else if (!strcmp(argv[i], "--stdin"))
	  opts.read_from_stdin = true;
	else if (!strcmp(argv[i], "--tu"))
	  opts.read_tu = true;
#ifdef WITH_CTF
        else if (!strcmp(argv[i], "--ctf"))
          opts.use_ctf = true;
#endif
	else if (!strcmp(argv[i], "--diff"))
	  opts.diff = true;
	else if (!strcmp(argv[i], "--noout"))
	  opts.noout = true;
#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
      else if (!strcmp(argv[i], "--show-type-use"))
	{
	  ++i;
	  if (i >= argc || argv[i][0] == '-')
	    return false;
	  opts.type_id_to_show = argv[i];
	}
#endif
	else
	  {
	    if (strlen(argv[i]) >= 2 && argv[i][0] == '-' && argv[i][1] == '-')
	      opts.wrong_option = argv[i];
	    return false;
	  }
      }

#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
    if (!opts.type_id_to_show.empty()
	&& opts.file_path.empty())
      emit_prefix(argv[0], cout)
	<< "WARNING: --show-type-use <type-id> "
	"must be accompanied with an abixml file\n";

    if (opts.file_path.empty()
	&& opts.type_id_to_show.empty())
      opts.read_from_stdin = true;
#endif

    if (opts.read_from_stdin && !opts.file_path.empty())
    {
      emit_prefix(argv[0], cout)
        << "WARNING: The \'--stdin\' option is used. The "
        << opts.file_path << " will be ignored automatically\n";
    }

    return true;
}

/// Check that the suppression specification files supplied are
/// present.  If not, emit an error on stderr.
///
/// @param opts the options instance to use.
///
/// @return true if all suppression specification files are present,
/// false otherwise.
static bool
maybe_check_suppression_files(const options& opts)
{
  for (vector<string>::const_iterator i = opts.suppression_paths.begin();
       i != opts.suppression_paths.end();
       ++i)
    if (!check_file(*i, cerr, "abidiff"))
      return false;

  return true;
}

/// Set suppression specifications to the @p read_context used to load
/// the ABI corpus from the ELF/DWARF file.
///
/// These suppression specifications are going to be applied to drop
/// some ABI artifacts on the floor (while reading the ELF/DWARF file
/// or the native XML ABI file) and thus minimize the size of the
/// resulting ABI corpus.
///
/// @param read_ctxt the read context to apply the suppression
/// specifications to.  Note that the type of this parameter is
/// generic (class template) because in practise, it can be either an
/// abigail::dwarf_reader::read_context type or an
/// abigail::xml_reader::read_context type.
///
/// @param opts the options where to get the suppression
/// specifications from.
template<class ReadContextType>
static void
set_suppressions(ReadContextType& read_ctxt, const options& opts)
{
  suppressions_type supprs;
  for (vector<string>::const_iterator i = opts.suppression_paths.begin();
       i != opts.suppression_paths.end();
       ++i)
    read_suppressions(*i, supprs);

  suppression_sptr suppr =
    abigail::tools_utils::gen_suppr_spec_from_headers(opts.headers_dir,
						      opts.header_files);
  if (suppr)
    supprs.push_back(suppr);

  add_read_context_suppressions(read_ctxt, supprs);
}

/// Reads a bi (binary instrumentation) file, saves it back to a
/// temporary file and run a diff on the two versions.
int
main(int argc, char* argv[])
{
  options opts;
  if (!parse_command_line(argc, argv, opts))
    {
      if (!opts.wrong_option.empty())
	emit_prefix(argv[0], cerr)
	  << "unrecognized option: " << opts.wrong_option << "\n";
      display_usage(argv[0], cerr);
      return 1;
    }

  if (opts.display_version)
    {
      emit_prefix(argv[0], cout)
	<< abigail::tools_utils::get_library_version_string()
	<< "\n";
      return 0;
    }

  if (!maybe_check_suppression_files(opts))
    return 1;

  abigail::ir::environment_sptr env(new abigail::ir::environment);
  if (opts.read_from_stdin)
    {
      if (!cin.good())
	return 1;

      if (opts.read_tu)
	{
	  abigail::translation_unit_sptr tu =
	    read_translation_unit_from_istream(&cin, env.get());

	  if (!tu)
	    {
	      emit_prefix(argv[0], cerr)
		<< "failed to read the ABI instrumentation from stdin\n";
	      return 1;
	    }

	  if (!opts.noout)
	    {
	      const write_context_sptr& ctxt
		  = create_write_context(tu->get_environment(), cout);
	      write_translation_unit(*ctxt, *tu, 0);
	    }
	  return 0;
	}
      else
	{
	  abigail::xml_reader::read_context_sptr ctxt =
	    abigail::xml_reader::create_native_xml_read_context(&cin,
								env.get());
	  assert(ctxt);
	  set_suppressions(*ctxt, opts);
	  corpus_sptr corp = abigail::xml_reader::read_corpus_from_input(*ctxt);
	  if (!opts.noout)
	    {
	      const write_context_sptr& ctxt
		  = create_write_context(corp->get_environment(), cout);
	      write_corpus(*ctxt, corp, /*indent=*/0);
	    }
	  return 0;
	}
    }
  else if (!opts.file_path.empty())
    {
      if (!check_file(opts.file_path, cerr, argv[0]))
	return 1;
      abigail::translation_unit_sptr tu;
      abigail::corpus_sptr corp;
      abigail::corpus_group_sptr group;
      abigail::elf_reader::status s = abigail::elf_reader::STATUS_OK;
      char* di_root_path = 0;
      file_type type = guess_file_type(opts.file_path);
      abigail::xml_reader::read_context_sptr abixml_read_ctxt;

      switch (type)
	{
	case abigail::tools_utils::FILE_TYPE_UNKNOWN:
	  emit_prefix(argv[0], cerr)
	    << "Unknown file type given in input: " << opts.file_path
	    << "\n";
	  return 1;
	case abigail::tools_utils::FILE_TYPE_NATIVE_BI:
	  {
	    abixml_read_ctxt =
	      abigail::xml_reader::create_native_xml_read_context(opts.file_path,
								  env.get());
	    tu = read_translation_unit(*abixml_read_ctxt);
	  }
	  break;
	case abigail::tools_utils::FILE_TYPE_ELF:
	case abigail::tools_utils::FILE_TYPE_AR:
	  {
	    di_root_path = opts.di_root_path.get();
	    vector<char**> di_roots;
	    di_roots.push_back(&di_root_path);

#ifdef WITH_CTF
            if (opts.use_ctf)
              {
                abigail::ctf_reader::read_context_sptr ctxt =
                  abigail::ctf_reader::create_read_context(opts.file_path,
                                                           di_roots,
                                                           env.get());
                ABG_ASSERT(ctxt);
                corp = abigail::ctf_reader::read_corpus(ctxt.get(), s);
              }
            else
#endif
              {
                abigail::dwarf_reader::read_context_sptr ctxt =
                  abigail::dwarf_reader::create_read_context(opts.file_path,
                                                             di_roots, env.get(),
                                                             /*load_all_types=*/false);
                assert(ctxt);
                set_suppressions(*ctxt, opts);
                corp = read_corpus_from_elf(*ctxt, s);
              }
	  }
	  break;
	case abigail::tools_utils::FILE_TYPE_XML_CORPUS:
	  {
	    abixml_read_ctxt =
	      abigail::xml_reader::create_native_xml_read_context(opts.file_path,
								  env.get());
	    assert(abixml_read_ctxt);
	    set_suppressions(*abixml_read_ctxt, opts);
	    corp = read_corpus_from_input(*abixml_read_ctxt);
	    break;
	  }
	case abigail::tools_utils::FILE_TYPE_XML_CORPUS_GROUP:
	  {
	    abixml_read_ctxt =
	      abigail::xml_reader::create_native_xml_read_context(opts.file_path,
								  env.get());
	    assert(abixml_read_ctxt);
	    set_suppressions(*abixml_read_ctxt, opts);
	    group = read_corpus_group_from_input(*abixml_read_ctxt);
	  }
	  break;
	case abigail::tools_utils::FILE_TYPE_RPM:
	  break;
	case abigail::tools_utils::FILE_TYPE_SRPM:
	  break;
	case abigail::tools_utils::FILE_TYPE_DEB:
	  break;
	case abigail::tools_utils::FILE_TYPE_DIR:
	  break;
	case abigail::tools_utils::FILE_TYPE_TAR:
	  break;
	}

      if (!tu && !corp && !group)
	{
	  emit_prefix(argv[0], cerr)
	    << "failed to read " << opts.file_path << "\n";
	  if (!(s & abigail::elf_reader::STATUS_OK))
	    {
	      if (s & abigail::elf_reader::STATUS_DEBUG_INFO_NOT_FOUND)
		{
		  cerr << "could not find the debug info";
		  if(di_root_path == 0)
		    emit_prefix(argv[0], cerr)
		      << " Maybe you should consider using the "
		      "--debug-info-dir1 option to tell me about the "
		      "root directory of the debuginfo? "
		      "(e.g, --debug-info-dir1 /usr/lib/debug)\n";
		  else
		    emit_prefix(argv[0], cerr)
		      << "Maybe the root path to the debug "
		      "information is wrong?\n";
		}
	      if (s & abigail::elf_reader::STATUS_NO_SYMBOLS_FOUND)
		emit_prefix(argv[0], cerr)
		  << "could not find the ELF symbols in the file "
		  << opts.file_path
		  << "\n";
	    }
	  return 1;
	}

      using abigail::tools_utils::temp_file;
      using abigail::tools_utils::temp_file_sptr;

      temp_file_sptr tmp_file = temp_file::create();
      if (!tmp_file)
	{
	  emit_prefix(argv[0], cerr) << "failed to create temporary file\n";
	  return 1;
	}

      std::ostream& of = opts.diff ? tmp_file->get_stream() : cout;
      const abigail::ir::environment* env = 0;
      if (tu)
	env = tu->get_environment();
      else if (corp)
	env = corp->get_environment();
      else if (group)
	env = group->get_environment();

      ABG_ASSERT(env);
      const write_context_sptr ctxt = create_write_context(env, of);

      bool is_ok = true;

      if (tu)
	{
	  if (!opts.noout)
	    is_ok = write_translation_unit(*ctxt, *tu, 0);
	}
      else
	{
	  if (type == abigail::tools_utils::FILE_TYPE_XML_CORPUS
	      || type == abigail::tools_utils::FILE_TYPE_XML_CORPUS_GROUP
	      || type == abigail::tools_utils::FILE_TYPE_ELF)
	    {
	      if (!opts.noout)
		{
		  if (corp)
		    is_ok = write_corpus(*ctxt, corp, 0);
		  else if (group)
		    is_ok = write_corpus_group(*ctxt, group, 0);
		}
	    }
	}

      if (!is_ok)
	{
	  string output =
	    (type == abigail::tools_utils::FILE_TYPE_NATIVE_BI)
	    ? "translation unit"
	    : "ABI corpus";
	  emit_prefix(argv[0], cerr)
	    << "failed to write the translation unit "
	    << opts.file_path << " back\n";
	}

      if (is_ok
	  && opts.diff
	  && ((type == abigail::tools_utils::FILE_TYPE_XML_CORPUS)
	      ||type == abigail::tools_utils::FILE_TYPE_XML_CORPUS_GROUP
	      || type == abigail::tools_utils::FILE_TYPE_NATIVE_BI))
	{
	  string cmd = "diff -u " + opts.file_path + " " + tmp_file->get_path();
	  if (system(cmd.c_str()))
	    is_ok = false;
	}

#ifdef WITH_SHOW_TYPE_USE_IN_ABILINT
      if (is_ok
	  && !opts.type_id_to_show.empty())
	{
	  ABG_ASSERT(abixml_read_ctxt);
	  show_how_type_is_used(*abixml_read_ctxt, opts.type_id_to_show);
	}
#endif
      return is_ok ? 0 : 1;
    }

  return 1;
}
