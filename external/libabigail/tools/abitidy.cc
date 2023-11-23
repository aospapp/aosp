// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
// -*- Mode: C++ -*-
//
// Copyright (C) 2021 Google, Inc.
//
// Author: Giuliano Procida

/// @file
///
/// This file contains ABI XML manipulation routines and a main driver.
///
/// The libxml Tree API is used. The XPath API is not used as it proved
/// to be many times slower than direct traversal but only slightly more
/// convenient.

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cassert>
#include <cctype>
#include <cstring>
#include <fstream>
#include <functional>
#include <iomanip>
#include <ios>
#include <iostream>
#include <map>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <libxml/globals.h>
#include <libxml/parser.h>
#include <libxml/tree.h>

/// Convenience typedef referring to a namespace scope.
using namespace_scope = std::vector<std::string>;

/// Convenience typedef referring to a set of symbols.
using symbol_set = std::unordered_set<std::string>;

/// Level of location information to preserve.
enum struct LocationInfo { COLUMN, LINE, FILE, NONE };

static const std::map<std::string, LocationInfo> LOCATION_INFO_NAME = {
  {"column", LocationInfo::COLUMN},
  {"line", LocationInfo::LINE},
  {"file", LocationInfo::FILE},
  {"none", LocationInfo::NONE},
};

static const std::map<std::string, std::string, std::less<>> NAMED_TYPES = {
  {"enum-decl", "__anonymous_enum__"},
  {"class-decl", "__anonymous_struct__"},
  {"union-decl", "__anonymous_union__"},
};

/// Compare optional strings.
///
/// TODO: Obsoleted by C++20 std::optional::operator<=>.
///
/// @param a first operand of comparison
///
/// @param b second operand of comparison
///
/// @return an integral result
int
compare_optional(const std::optional<std::string>& a,
                 const std::optional<std::string>& b)
{
  int result = b.has_value() - a.has_value();
  if (result)
    return result;
  return a ? a.value().compare(b.value()) : 0;
}

/// Cast a C string to a libxml string.
///
/// @param str the C string (pointer)
///
/// @return the same thing, as a type compatible with the libxml API
static const xmlChar*
to_libxml(const char* str)
{
  return reinterpret_cast<const xmlChar*>(str);
}

/// Cast a libxml string to C string.
///
/// @param str the libxml string (pointer)
///
/// @return the same thing, as a type compatible with the C library API
static const char*
from_libxml(const xmlChar* str)
{
  return reinterpret_cast<const char*>(str);
}

/// Get comment node corresponding to a given node if it exists.
///
/// Returns nullptr if previous node does not exist or is not a comment,
/// otherwise returns the previous node.
///
/// @param node the node for which comment has to be returned
///
/// @return pointer to the comment node
static xmlNodePtr
get_comment_node(xmlNodePtr node)
{
  xmlNodePtr previous_node = node->prev;
  return previous_node && previous_node->type == XML_COMMENT_NODE
      ? previous_node : nullptr;
}

/// Remove a node from its document and free its storage.
///
/// @param node the node to remove
static void
remove_node(xmlNodePtr node)
{
  xmlUnlinkNode(node);
  xmlFreeNode(node);
}

/// Remove an XML element and any immediately preceding comment.
///
/// @param node the element to remove
static void
remove_element(xmlNodePtr node)
{
  if (auto comment_node = get_comment_node(node))
    remove_node(comment_node);
  remove_node(node);
}

/// Move a node to an element.
///
/// @param node the node to move
///
/// @param destination the destination element
static void
move_node(xmlNodePtr node, xmlNodePtr destination)
{
  xmlUnlinkNode(node);
  xmlAddChild(destination, node);
}

/// Move an XML element and any immediately preceding comment to another
/// element.
///
/// @param node the element to remove
///
/// @param destination the destination element
static void
move_element(xmlNodePtr node, xmlNodePtr destination)
{
  if (auto comment_node = get_comment_node(node))
    move_node(comment_node, destination);
  move_node(node, destination);
}

/// Get child nodes of given node.
///
/// @param node the node whose children to fetch
///
/// @return a vector of child nodes
static std::vector<xmlNodePtr>
get_children(xmlNodePtr node)
{
  std::vector<xmlNodePtr> result;
  for (xmlNodePtr child = node->children; child; child = child->next)
    result.push_back(child);
  return result;
}

/// Fetch an attribute from a node.
///
/// @param node the node
///
/// @param name the attribute name
///
/// @return the attribute value, if present
static std::optional<std::string>
get_attribute(xmlNodePtr node, const char* name)
{
  std::optional<std::string> result;
  xmlChar* attribute = xmlGetProp(node, to_libxml(name));
  if (attribute)
    {
      result = from_libxml(attribute);
      xmlFree(attribute);
    }
  return result;
}

/// Set an attribute value.
///
/// @param node the node
///
/// @param name the attribute name
///
/// @param value the attribute value
static void
set_attribute(xmlNodePtr node, const char* name,
              const std::string& value)
{
  xmlSetProp(node, to_libxml(name), to_libxml(value.c_str()));
}

/// Unset an attribute value.
///
/// @param node the node
///
/// @param name the attribute name
static void
unset_attribute(xmlNodePtr node, const char* name)
{
  xmlUnsetProp(node, to_libxml(name));
}

/// Remove text nodes, recursively.
///
/// This simplifies subsequent analysis and manipulation. Removing and
/// moving elements will destroy formatting anyway. The only remaining
/// node types should be elements and comments.
///
/// @param node the node to process
static void
strip_text(xmlNodePtr node)
{
  if (node->type == XML_TEXT_NODE)
    remove_node(node);
  else if (node->type == XML_ELEMENT_NODE)
    for (xmlNodePtr child : get_children(node))
      strip_text(child);
}

/// Add text before / after a node.
///
/// @param node the node
///
/// @param after whether the next should go after
///
/// @param text the text
static void
add_text(xmlNodePtr node, bool after, const std::string& text)
{
  xmlNodePtr text_node = xmlNewTextLen(to_libxml(text.data()), text.size());
  if (after)
    xmlAddNextSibling(node, text_node);
  else
    xmlAddPrevSibling(node, text_node);
}

/// Format an XML element by adding internal indentation and newlines.
///
/// This makes the XML readable.
///
/// @param indentation what to add to the line indentation prefix
///
/// @param prefix the current line indentation prefix
///
/// @param node the node to format
static void
format_xml(const std::string& indentation, std::string prefix, xmlNodePtr node)
{
  std::vector<xmlNodePtr> children = get_children(node);
  if (children.empty())
    return;

  // The ordering of operations here is incidental. The outcomes we want
  // are: 1. an extra newline after the opening tag and indentation of
  // the closing tag to match, and 2. indentation and newline for each
  // child.
  add_text(children[0], false, "\n");
  add_text(children[children.size() - 1], true, prefix);
  prefix += indentation;
  for (xmlNodePtr child : children)
    {
      add_text(child, false, prefix);
      format_xml(indentation, prefix, child);
      add_text(child, true, "\n");
    }
}

/// Rewrite attributes using single quotes.
///
/// libxml uses double quotes but libabigail uses single quotes.
///
/// Note that libabigail does not emit attributes *containing* single
/// quotes and if it did it would escape them as &quot; which libxml
/// would in turn preserve. However, the code here will handle all forms
/// of quotes, conservatively.
///
/// Annotation comments can contain single quote characters so just
/// checking for any single quotes at all is insufficiently precise.
///
/// @param start a pointer to the start of the XML text
///
/// @param limit a pointer to just past the end of the XML text
static void
adjust_quotes(xmlChar* start, xmlChar* limit)
{
  const std::string open{"<!--"};
  const std::string close{"-->"};
  while (start < limit)
    {
      // Look for a '<'
      start = std::find(start, limit, '<');
      if (start == limit)
        break;
      if (start + open.size() < limit
          && std::equal(open.begin(), open.end(), start))
        {
          // Have a comment, skip to the end.
          start += open.size();
          xmlChar* end = std::search(start, limit, close.begin(), close.end());
          if (end == limit)
            break;
          start = end + close.size();
        }
      else
        {
          // Have some tag, search for the end.
          start += 1;
          xmlChar* end = std::find(start, limit, '>');
          if (end == limit)
            break;
          // In general, inside a tag we could find either ' or " being
          // used to quote attributes and the other quote character
          // being used as part of the attribute data. However, libxml's
          // xmlDocDump* functions use " to quote attributes and it's
          // safe to substitute this quote character with ' so long as '
          // does not appear within the attribute data.
          if (std::find(start, end, '\'') == end)
            for (xmlChar* c = start; c < end; ++c)
              if (*c == '"')
                *c = '\'';
          start = end + 1;
        }
    }
}

/// Compare given attribute of 2 XML nodes.
///
/// @param attribute the attribute to compare
///
/// @param a first XML node to compare
///
/// @param b second XML node to compare
///
/// @return an integral result
static int
compare_attributes(
    const char* attribute, const xmlNodePtr& a, const xmlNodePtr& b)
{
  return compare_optional(get_attribute(a, attribute),
                          get_attribute(b, attribute));
}

static const std::set<std::string> DROP_IF_EMPTY = {
  "elf-variable-symbols",
  "elf-function-symbols",
  "namespace-decl",
  "abi-instr",
  "abi-corpus",
  "abi-corpus-group",
};

/// Drop empty elements, if safe to do so, recursively.
///
/// @param node the element to process
static void
drop_empty(xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;
  for (xmlNodePtr child : get_children(node))
    drop_empty(child);
  // Do not drop the root element, even if empty.
  if (node->parent->type == XML_DOCUMENT_NODE)
    return;
  if (!node->children && DROP_IF_EMPTY.count(from_libxml(node->name)))
    remove_element(node);
}

/// Get ELF symbol id.
///
/// This is not an explicit attribute. It takes one of these forms:
///
/// * name (if symbol is not versioned)
/// * name@version (if symbol is versioned but not the default version)
/// * name@@version (if symbol is versioned and the default version)
///
/// @param node the elf-symbol element
///
/// @return the ELF symbol id
static std::string
get_elf_symbol_id(xmlNodePtr node)
{
  const auto name = get_attribute(node, "name");
  assert(name);
  std::string result = name.value();
  const auto version = get_attribute(node, "version");
  if (version)
    {
      result += '@';
      const auto is_default = get_attribute(node, "is-default-version");
      if (is_default && is_default.value() == "yes")
        result += '@';
      result += version.value();
    }
  return result;
}

static const std::set<std::string> HAS_LOCATION = {
  "class-decl",
  "enum-decl",
  "function-decl",
  "parameter",
  "typedef-decl",
  "union-decl",
  "var-decl"
};

/// Limit location information.
///
/// @param location_info the level of location information to retain
///
/// @param node the element to process
static void
limit_locations(LocationInfo location_info, xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;
  if (HAS_LOCATION.count(from_libxml(node->name)))
    {
      if (location_info > LocationInfo::COLUMN)
        {
          unset_attribute(node, "column");
          if (location_info > LocationInfo::LINE)
            {
              unset_attribute(node, "line");
              if (location_info > LocationInfo::FILE)
                unset_attribute(node, "filepath");
            }
        }
    }
  for (xmlNodePtr child : get_children(node))
    limit_locations(location_info, child);
}

/// Handle unreachable elements.
///
/// Reachability is defined to be union of contains, containing and
/// refers-to relationships for types, declarations and symbols. The
/// roots for reachability are the ELF elements in the ABI.
///
/// The subrange element requires special treatment. It has a useless
/// type id, but it is not a type and its type id aliases with that of
/// all other subranges of the same length. So don't treat it as a type.
///
/// @param prune whether to prune unreachable elements
///
/// @param report whether to report untyped symbols
///
/// @param alias_map mapping from alias to main elf-symbol-id
///
/// @param root the XML root element
///
/// @return the number of untyped symbols
static size_t
handle_unreachable(
    bool prune, bool report,
    const std::unordered_map<std::string, std::string>& alias_map,
    xmlNodePtr root)
{
  // ELF symbol ids.
  std::set<std::string> elf_symbol_ids;

  // Simple way of allowing two kinds of nodes: false=>type,
  // true=>symbol.
  using vertex_t = std::pair<bool, std::string>;

  // Graph vertices.
  std::set<vertex_t> vertices;
  // Graph edges.
  std::map<vertex_t, std::set<vertex_t>> edges;

  // Keep track of type / symbol nesting so we can identify contains,
  // containing and refers-to relationships.
  std::vector<vertex_t> stack;

  // Process an XML node, adding a vertex and possibly some edges.
  std::function<void(xmlNodePtr)> process_node = [&](xmlNodePtr node) {
    // We only care about elements and not comments, at this stage.
    if (node->type != XML_ELEMENT_NODE)
      return;

    const char* node_name = from_libxml(node->name);

    // Is this an ELF symbol?
    if (strcmp(node_name, "elf-symbol") == 0)
      {
        elf_symbol_ids.insert(get_elf_symbol_id(node));
        // Early return is safe, but not necessary.
        return;
      }

    // Is this a type? Note that the same id may appear multiple times.
    const auto id = strcmp(node_name, "subrange") != 0
                    ? get_attribute(node, "id")
                    : std::optional<std::string>();
    if (id)
      {
        vertex_t type_vertex{false, id.value()};
        vertices.insert(type_vertex);
        const auto naming_typedef_id = get_attribute(node, "naming-typedef-id");
        if (naming_typedef_id)
          {
            // This is an odd one, there can be a backwards link from an
            // anonymous type to a typedef that refers to it. The -t
            // option will drop these, but if they are still present, we
            // should model the link to avoid the risk of dangling
            // references.
            vertex_t naming_typedef_vertex{false, naming_typedef_id.value()};
            edges[type_vertex].insert(naming_typedef_vertex);
          }
        if (!stack.empty())
          {
            // Parent<->child dependencies; record dependencies both
            // ways to avoid holes in XML types and declarations.
            const auto& parent = stack.back();
            edges[parent].insert(type_vertex);
            edges[type_vertex].insert(parent);
          }
        // Record the type.
        stack.push_back(type_vertex);
      }

    // Is this a (declaration expected to be linked to a) symbol?
    const auto symbol = get_attribute(node, "elf-symbol-id");
    if (symbol)
      {
        vertex_t symbol_vertex{true, symbol.value()};
        vertices.insert(symbol_vertex);
        if (!stack.empty())
          {
            // Parent<->child dependencies; record dependencies both
            // ways to avoid making holes in XML types and declarations.
            //
            // Symbols exist outside of the type hierarchy, so choosing
            // to make them depend on a containing type scope and vice
            // versa is conservative and probably not necessary.
            const auto& parent = stack.back();
            edges[parent].insert(symbol_vertex);
            edges[symbol_vertex].insert(parent);
          }
        // Record the symbol.
        stack.push_back(symbol_vertex);
        // In practice there will be at most one symbol on the stack; we could
        // verify this here, but it wouldn't achieve anything.
      }

    // Being both would make the stack ordering ambiguous.
    if (id && symbol)
      {
        std::cerr << "cannot handle element which is both type and symbol\n";
        exit(1);
      }

    // Is there a reference to another type?
    const auto type_id = get_attribute(node, "type-id");
    if (type_id && !stack.empty())
      {
        // The enclosing type or symbol refers to another type.
        const auto& parent = stack.back();
        vertex_t type_id_vertex{false, type_id.value()};
        edges[parent].insert(type_id_vertex);
      }

    // Process recursively.
    for (auto child : get_children(node))
      process_node(child);

    // Restore the stack.
    if (symbol)
      stack.pop_back();
    if (id)
      stack.pop_back();
  };

  // Traverse the whole root element and build a graph.
  process_node(root);

  // Simple DFS.
  std::set<vertex_t> seen;
  std::function<void(vertex_t)> dfs = [&](vertex_t vertex) {
    if (!seen.insert(vertex).second)
      return;
    auto it = edges.find(vertex);
    if (it != edges.end())
      for (auto to : it->second)
        dfs(to);
  };

  // Count of how many symbols are untyped.
  size_t untyped = 0;

  // Traverse the graph, starting from the ELF symbols.
  for (const auto& symbol_id : elf_symbol_ids)
    {
      const auto it = alias_map.find(symbol_id);
      const auto& mapped_symbol_id =
          it != alias_map.end() ? it->second : symbol_id;
      vertex_t symbol_vertex{true, mapped_symbol_id};
      if (vertices.count(symbol_vertex))
        {
          dfs(symbol_vertex);
        }
      else
        {
          if (report)
            std::cerr << "no declaration found for ELF symbol with id "
                      << symbol_id << '\n';
          ++untyped;
        }
    }

  // This is a DFS with early stopping.
  std::function<void(xmlNodePtr)> remove_unseen = [&](xmlNodePtr node) {
    if (node->type != XML_ELEMENT_NODE)
      return;

    const char* node_name = from_libxml(node->name);

    // Return if we know that this is a type to keep or drop in its
    // entirety.
    const auto id = strcmp(node_name, "subrange") != 0
                    ? get_attribute(node, "id")
                    : std::optional<std::string>();
    if (id)
      {
        if (!seen.count(vertex_t{false, id.value()}))
          remove_element(node);
        return;
      }

    // Return if we know that this is a declaration to keep or drop in
    // its entirety. Note that var-decl and function-decl are the only
    // elements that can have an elf-symbol-id attribute.
    if (strcmp(node_name, "var-decl") == 0
        || strcmp(node_name, "function-decl") == 0)
      {
        const auto symbol = get_attribute(node, "elf-symbol-id");
        if (!(symbol && seen.count(vertex_t{true, symbol.value()})))
          remove_element(node);
        return;
      }

    // Otherwise, this is not a type, declaration or part thereof, so
    // process child elements.
    for (auto child : get_children(node))
      remove_unseen(child);
  };

  if (prune)
    // Traverse the XML, removing unseen elements.
    remove_unseen(root);

  return untyped;
}

/// Tidy anonymous types in various ways.
///
/// 1. Normalise anonymous type names by removing the numerical suffix.
///
/// Anonymous type names take the form __anonymous_foo__N where foo is
/// one of enum, struct or union and N is an optional numerical suffix.
/// The suffices are senstive to processing order and do not convey
/// useful ABI information. They can cause spurious harmless diffs and
/// make XML diffing and rebasing harder.
///
/// It's best to remove the suffix.
///
/// 2. Reanonymise anonymous types that have been given names.
///
/// A recent change to abidw changed its behaviour for any anonymous
/// type that has a naming typedef. In addition to linking the typedef
/// and type in both directions, the code now gives (some) anonymous
/// types the same name as the typedef. This misrepresents the original
/// types.
///
/// Such types should be anonymous.
///
/// 3. Discard naming typedef backlinks.
///
/// The attribute naming-typedef-id is a backwards link from an
/// anonymous type to the typedef that refers to it. It is ignored by
/// abidiff.
///
/// Unfortunately, libabigail sometimes conflates multiple anonymous
/// types that have naming typedefs and only one of the typedefs can
/// "win". ABI XML is thus sensitive to processing order and can also
/// end up containing definitions of an anonymous type with differing
/// naming-typedef-id attributes.
///
/// It's best to just drop the attribute.
///
/// @param node the XML node to process
static void
handle_anonymous_types(bool normalise, bool reanonymise, bool discard_naming,
                       xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  const auto it = NAMED_TYPES.find(from_libxml(node->name));
  if (it != NAMED_TYPES.end())
    {
      const auto& anon = it->second;
      const auto name_attribute = get_attribute(node, "name");
      const auto& name =
          name_attribute ? name_attribute.value() : std::string();
      const auto anon_attr = get_attribute(node, "is-anonymous");
      const bool is_anon = anon_attr && anon_attr.value() == "yes";
      const auto naming_attribute = get_attribute(node, "naming-typedef-id");
      if (normalise && is_anon && name != anon) {
        // __anonymous_foo__123 -> __anonymous_foo__
        set_attribute(node, "name", anon);
      }
      if (reanonymise && !is_anon && naming_attribute) {
        // bar with naming typedef -> __anonymous_foo__
        set_attribute(node, "is-anonymous", "yes");
        set_attribute(node, "name", anon);
      }
      if (discard_naming && naming_attribute)
        unset_attribute(node, "naming-typedef-id");
    }

  for (auto child : get_children(node))
    handle_anonymous_types(normalise, reanonymise, discard_naming, child);
}

/// Builds a mapping from qualified types to the underlying type ids.
///
/// Recursively constructs a mapping from qualified types to the underlying
/// type ids found in the XML tree rooted at the given node.
///
/// @param node node of the XML tree to process
///
/// @param qualifier_id_to_type_id map from qualified types to underlying type
/// ids being constructed
static void
build_qualifier_id_to_type_id_map(
    const xmlNodePtr node,
    std::unordered_map<std::string, std::string>& qualifier_id_to_type_id)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  if (strcmp(from_libxml(node->name), "qualified-type-def") == 0)
    {
      const auto id = get_attribute(node, "id");
      const auto type_id = get_attribute(node, "type-id");
      if (!id || !type_id)
        {
          std::cerr << "found qualified type definition with missing id and/or "
                    << "type id\nid: " << id.value_or("(missing)")
                    << "\ntype id: " << type_id.value_or("(missing)") << '\n';
          exit(1);
        }
      const auto& id_value = id.value();
      const auto& type_id_value = type_id.value();
      auto [it, inserted] =
          qualifier_id_to_type_id.insert({id_value, type_id_value});
      if (!inserted && it->second != type_id_value)
        {
          std::cerr << "conflicting type ids ('" << it->second << "' & '"
                    << type_id_value << "') found for qualified type with "
                    << "id: " << id_value << '\n';
          exit(1);
        }
    }
  else
    {
      for (auto child : get_children(node))
        build_qualifier_id_to_type_id_map(child, qualifier_id_to_type_id);
    }
}

/// Determine mapping from qualified type to underlying unqualified type.
///
/// This resolves chains of qualifiers on qualified types. Note that this does
/// not attempt to look through typedefs.
///
/// @param qualifier_id_to_type_id map from qualified types to underlying type
/// ids
static void
resolve_qualifier_chains(
    std::unordered_map<std::string, std::string>& qualifier_id_to_type_id)
{
  for (auto& [id, type_id] : qualifier_id_to_type_id)
    {
      std::unordered_set<std::string> seen;
      while (true)
        {
          if (!seen.insert(type_id).second)
            {
              std::cerr << "dequalification of type with id '" << id
                        << "' ran into a self referencing loop\n";
              exit(1);
            }
          auto it = qualifier_id_to_type_id.find(type_id);
          if (it == qualifier_id_to_type_id.end())
            break;
          type_id = it->second;
        }
    }
}

/// Removes top-level qualifiers from function parameter and return types.
///
/// Recursively removes top-level qualifiers from parameter and return types of
/// all function declarations and function types found in the XML tree rooted
/// at the given node.
///
/// This requires also requires a map of qualified types to the underlying type
/// ids, which enables the unqualification of qualified types.
///
/// @param node node of the XML tree to process
///
/// @param qualifier_id_to_type_id map from qualified types to underlying type
/// ids
static void
remove_function_parameter_type_qualifiers(
    const xmlNodePtr node,
    const std::unordered_map<std::string, std::string>& qualifier_id_to_type_id)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  if (strcmp(from_libxml(node->name), "function-decl") == 0 ||
      strcmp(from_libxml(node->name), "function-type") == 0)
    {
      bool type_changed = false;
      for (auto child : get_children(node))
        if (const auto type_id = get_attribute(child, "type-id"))
          {
            const auto& type_id_value = type_id.value();
            auto it = qualifier_id_to_type_id.find(type_id_value);
            if (it != qualifier_id_to_type_id.end())
              {
                type_changed = true;
                set_attribute(child, "type-id", it->second);

                // Parameter or return type has been modified, making a comment
                // describing the type for this node inconsistent. Thus the
                // comment must be removed if it exists.
                if (auto comment_node = get_comment_node(child))
                  remove_node(comment_node);
              }
          }

      if (type_changed)
        {
          // Parameter or return type has been modified, making a comment
          // describing the type for this node inconsistent. Thus the comment
          // must be removed if it exists.
          if (auto comment_node = get_comment_node(node))
            remove_node(comment_node);
        }
    }
  else
    {
      for (auto child : get_children(node))
        remove_function_parameter_type_qualifiers(child, qualifier_id_to_type_id);
    }
}

/// Remove attributes emitted by abidw --load-all-types.
///
/// With this invocation and if any user-defined types are deemed
/// unreachable, libabigail will output a tracking-non-reachable-types
/// attribute on top-level elements and a is-non-reachable attribute on
/// each such type element.
///
/// abitidy has its own graph-theoretic notion of reachability and these
/// attributes have no ABI relevance.
///
/// It's best to just drop them.
///
/// @param node the XML node to process
void
clear_non_reachable(xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  const char* node_name = from_libxml(node->name);

  if (strcmp(node_name, "abi-corpus-group") == 0
      || strcmp(node_name, "abi-corpus") == 0)
    unset_attribute(node, "tracking-non-reachable-types");
  else if (NAMED_TYPES.find(node_name) != NAMED_TYPES.end())
    unset_attribute(node, "is-non-reachable");

  for (auto child : get_children(node))
    clear_non_reachable(child);
}

/// Determine the effective name of a given node.
///
/// The effective name is same as the value of the 'name' attribute for all
/// nodes except nodes which represent anonymous types. For anonymous types, the
/// function returns std::nullopt.
///
/// @param node the node for which effective name has to be determined
///
/// @return an optional name string
std::optional<std::string>
get_effective_name(xmlNodePtr node)
{
  return get_attribute(node, "is-anonymous")
      ? std::nullopt : get_attribute(node, "name");
}

/// Record type ids for anonymous types that have to be renumbered.
///
/// This constructs a map from the ids that need to be renumbered to the XML
/// node where the id is defined/declared. Also records hexadecimal hashes used
/// by non-anonymous types.
///
/// @param node the node being processed
///
/// @param to_renumber map from ids to be renumbered to corresponding XML node
///
/// @param used_hashes set of hashes used by non-anonymous type ids
static void
record_ids_to_renumber(
    xmlNodePtr node,
    std::unordered_map<std::string, xmlNodePtr>& to_renumber,
    std::unordered_set<size_t>& used_hashes)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  for (auto child : get_children(node))
    record_ids_to_renumber(child, to_renumber, used_hashes);

  const auto& id_attr = get_attribute(node, "id");
  if (!id_attr)
    return;

  const auto& id = id_attr.value();
  const std::string_view node_name(from_libxml(node->name));
  const bool is_anonymous_type_candidate = NAMED_TYPES.count(node_name);
  if (!is_anonymous_type_candidate || get_effective_name(node))
    {
      const bool is_hexadecimal = std::all_of(
          id.begin(), id.end(), [](unsigned char c){ return std::isxdigit(c); });
      if (id.size() == 8 && is_hexadecimal)
        {
          // Do not check for successful insertion since there can be multiple
          // declarations/definitions for a type.
          size_t hash = std::stoul(id, nullptr, 16);
          used_hashes.insert(hash);
        }
    }
  else
    {
      // Check for successful insertion since anonymous types are not prone to
      // having multiple definitions/declarations.
      if (!to_renumber.insert({id, node}).second)
        {
          std::cerr << "Found multiple definitions/declarations of anonmyous "
                    << "type with id: " << id << '\n';
          exit(1);
        }
    }
}

/// Compute a stable string hash.
///
/// This is the 32-bit FNV-1a algorithm. The algorithm, reference code
/// and constants are all unencumbered. It is fast and has reasonable
/// distribution properties.
///
/// std::hash has no portability or stability guarantees so is
/// unsuitable where reproducibility is a requirement such as in XML
/// output.
///
/// https://en.wikipedia.org/wiki/Fowler-Noll-Vo_hash_function
///
/// @param str the string to hash
///
/// @return an unsigned 32 bit hash value
static uint32_t
fnv_hash(const std::string& str)
{
  const uint32_t prime = 0x01000193;
  const uint32_t offset_basis = 0x811c9dc5;
  uint32_t hash = offset_basis;
  for (const char& c : str)
    {
      uint8_t byte = c;
      hash = hash ^ byte;
      hash = hash * prime;
    }
  return hash;
}

/// Generate a new 32 bit type id and return its hexadecimal representation.
///
/// Generates hash of the given hash content. Uses linear probing to resolve
/// hash collisions. Also, records the newly generated hash in a set of used
/// hashes.
///
/// @param hash_content the string which is used to generate a hash
///
/// @param used_hashes the set of hashes which have already been used
///
/// @return the hexadecimal representation of the newly generated hash
static std::string
generate_new_id(const std::string& hash_content,
                std::unordered_set<size_t>& used_hashes)
{
  auto hash = fnv_hash(hash_content);
  while (!used_hashes.insert(hash).second)
    ++hash;
  std::ostringstream os;
  os << std::hex << std::setfill('0') << std::setw(8) << hash;
  return os.str();
}

/// Find the first member for a user defined type.
///
/// The first member for enums is the first enumerator while for structs and
/// unions it is the variable declaration of the first data member.
///
/// @param node the node being processed
///
/// @return the node which represents the first member
static xmlNodePtr
find_first_member(xmlNodePtr node)
{
  auto first_child_by_xml_node_name =
      [](const xmlNodePtr node, const std::string_view name) -> xmlNodePtr {
    for (auto child : get_children(node))
      if (child->type == XML_ELEMENT_NODE && from_libxml(child->name) == name)
        return child;
    return nullptr;
  };

  if (strcmp(from_libxml(node->name), "enum-decl") == 0)
      return first_child_by_xml_node_name(node, "enumerator");
  if (auto data_member = first_child_by_xml_node_name(node, "data-member"))
      return first_child_by_xml_node_name(data_member, "var-decl");
  return nullptr;
}

/// Calculate new type id for a given old type id.
///
/// This resolves the old type ids for anonymous types to new ones, while ids
/// which do not belong to anonymous types are returned as they are.
///
/// @param type_id old type id
///
/// @param to_renumber map from ids to be renumbered to corresponding XML node
///
/// @param used_hashes set of hashes used by other type ids
///
/// @param type_id_map mapping from old type ids to new ones
///
/// @return resolved type id
static std::string
resolve_ids_to_renumber(
    const std::string& type_id,
    const std::unordered_map<std::string, xmlNodePtr>& to_renumber,
    std::unordered_set<size_t>& used_hashes,
    std::unordered_map<std::string, std::string>& type_id_map)
{
  // Check whether the given type_id needs to be renumbered. If not, the type_id
  // can be returned since it does not represent an anonymous type.
  const auto to_renumber_it = to_renumber.find(type_id);
  if (to_renumber_it == to_renumber.end())
    return type_id;

  // Insert an empty string placeholder to prevent infinite loops.
  const auto& [type_mapping, inserted] = type_id_map.insert({type_id, {}});
  if (!inserted)
    {
      if (!type_mapping->second.empty())
        return type_mapping->second;
      std::cerr << "new type id depends on itself for type with id: "
                << type_id << '\n';
      exit(1);
    }

  const auto& node = to_renumber_it->second;
  std::ostringstream hash_content;
  hash_content << from_libxml(node->name);
  if (auto first_member = find_first_member(node))
    {
      // Create hash content by combining the name & resolved type id of the
      // first member and the kind of anonymous type.
      if (auto name = get_effective_name(first_member))
        hash_content << '-' << name.value();
      if (auto type_id = get_attribute(first_member, "type-id"))
        hash_content << '-' << resolve_ids_to_renumber(
            type_id.value(), to_renumber, used_hashes, type_id_map);
    }
  else
    {
      // No member information available. Possibly type is empty.
      hash_content << "__empty";
    }

  return type_mapping->second =
      generate_new_id(hash_content.str(), used_hashes);
}

/// Replace old type ids by new ones.
///
/// @param node the node which is being processed
///
/// @param type_id_map map from old type ids to replace to new ones
static void
renumber_type_ids(
    xmlNodePtr node,
    const std::unordered_map<std::string, std::string>& type_id_map)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  auto maybe_replace = [&](const char* attribute_name) {
    const auto& attribute = get_attribute(node, attribute_name);
    if (attribute)
      {
        const auto it = type_id_map.find(attribute.value());
        if (it != type_id_map.end())
          set_attribute(node, attribute_name, it->second);
      }
  };

  maybe_replace("id");
  maybe_replace("type-id");
  maybe_replace("naming-typedef-id");

  for (auto child : get_children(node))
    renumber_type_ids(child, type_id_map);
}

/// Determine whether one XML element is the same as another.
///
/// XML elements representing members are sometimes emitted multiple
/// times, identically.
///
/// @param left the first element to compare
///
/// @param right the second element to compare
///
/// @return whether the first element is the same as the second
bool
equal_tree(xmlNodePtr left, xmlNodePtr right)
{
  // Node names must match.
  const char* left_name = from_libxml(left->name);
  const char* right_name = from_libxml(right->name);
  if (strcmp(left_name, right_name) != 0)
    return false;

  // Attributes must match.
  std::unordered_set<std::string> attributes;
  for (auto properties : {left->properties, right->properties})
    for (auto property = properties; property; property = property->next)
      attributes.insert(from_libxml(property->name));
  for (const auto& attribute : attributes)
    {
      const char* name = attribute.c_str();
      const auto left_value = get_attribute(left, name);
      const auto right_value = get_attribute(right, name);
      if (left_value != right_value)
        return false;
    }

  // The left subelements must be the same as the right ones.
  xmlNodePtr left_child = xmlFirstElementChild(left);
  xmlNodePtr right_child = xmlFirstElementChild(right);
  while (left_child && right_child)
    {
      if (!equal_tree(left_child, right_child))
        return false;
      left_child = xmlNextElementSibling(left_child);
      right_child = xmlNextElementSibling(right_child);
    }
  if (left_child || right_child)
    return false;
  return true;
}

/// Determine whether one XML element is a subtree of another.
///
/// XML elements representing types are sometimes emitted multiple
/// times, identically. Also, member typedefs are sometimes emitted
/// separately from their types, resulting in duplicate XML fragments.
///
/// Both these issues can be resolved by first detecting duplicate
/// occurrences of a given type id and then checking to see if there's
/// an instance that subsumes the others, which can then be eliminated.
///
/// @param left the first element to compare
///
/// @param right the second element to compare
///
/// @return whether the first element is a subtree of the second
bool
sub_tree(xmlNodePtr left, xmlNodePtr right)
{
  // The set of attributes that should be excluded from consideration when
  // comparing XML elements. These attributes are either irrelevant for ABI
  // monitoring or already handled by another check.
  static const std::unordered_set<std::string> IRRELEVANT_ATTRIBUTES = {
    // Source location information. This can vary between duplicate type
    // definitions.
    "filepath",
    "line",
    "column",
    // Anonymous type to typedef backlinks.
    "naming-typedef-id",
    // Annotation that can appear with --load-all-types.
    "is-non-reachable",
    // Handled while checking for effective name equivalence.
    "name",
    "is-anonymous",
  };

  // Node names must match.
  const char* left_name = from_libxml(left->name);
  const char* right_name = from_libxml(right->name);
  if (strcmp(left_name, right_name) != 0)
    return false;

  // Effective names must match.
  if (get_effective_name(left) != get_effective_name(right))
    return false;

  // Attributes may be missing on the left, but must match otherwise.
  for (auto p = left->properties; p; p = p->next)
  {
    const char* attribute_name = from_libxml(p->name);
    if (IRRELEVANT_ATTRIBUTES.count(attribute_name))
      continue;
    // EXCEPTION: libabigail emits the access specifier for the type
    // it's trying to "emit in scope" rather than for what may be a
    // containing type; so allow member-type attribute access to differ.
    if (strcmp(left_name, "member-type") == 0
        && strcmp(attribute_name, "access") == 0)
      continue;
    const auto left_value = get_attribute(left, attribute_name);
    assert(left_value);
    const auto right_value = get_attribute(right, attribute_name);
    if (!right_value || left_value.value() != right_value.value())
      return false;
  }

  // The left subelements must be a subsequence of the right ones.
  xmlNodePtr left_child = xmlFirstElementChild(left);
  xmlNodePtr right_child = xmlFirstElementChild(right);
  while (left_child && right_child)
    {
      if (sub_tree(left_child, right_child))
        left_child = xmlNextElementSibling(left_child);
      right_child = xmlNextElementSibling(right_child);
    }
  return !left_child;
}


/// Handle excess data members.
///
/// @param eliminate whether to eliminate
///
/// @param root the root XML element
///
/// @return the number of excess members
size_t handle_excess_members(bool eliminate, xmlNodePtr root)
{
  std::vector<xmlNodePtr> types;

  // find all structs and unions
  std::function<void(xmlNodePtr)> dfs = [&](xmlNodePtr node) {
    if (node->type != XML_ELEMENT_NODE)
      return;
    const char* node_name = from_libxml(node->name);
    // preorder in case we delete a nested type
    for (auto child : get_children(node))
      dfs(child);
    if (strcmp(node_name, "class-decl") || strcmp(node_name, "union-decl"))
      types.push_back(node);
  };
  dfs(root);

  size_t count = 0;
  for (const auto& node : types)
    {
      // filter data members (skipping things like comments)
      std::vector<xmlNodePtr> data_members;
      for (auto child : get_children(node))
        if (strcmp(from_libxml(child->name), "data-member") == 0)
          data_members.push_back(child);
      // look for identical duplicate data members - O(n^2)
      for (size_t i = 0; i < data_members.size(); ++i)
        {
          const xmlNodePtr i_node = data_members[i];
          bool duplicate = false;
          for (size_t j = 0; j < i; ++j)
            {
              const xmlNodePtr j_node = data_members[j];
              if (j_node != nullptr && equal_tree(i_node, j_node))
                {
                  duplicate = true;
                  break;
                }
            }
          if (duplicate)
            {
              std::cerr << "found duplicate data-member\n";
              if (eliminate) {
                remove_element(i_node);
                data_members[i] = nullptr;
              }
              ++count;
            }
        }
    }
  return count;
}

/// Eliminate non-conflicting / report conflicting duplicate definitions.
///
/// This function can eliminate exact type duplicates and duplicates
/// where there is at least one maximal definition. It can report the
/// remaining, conflicting duplicate definitions.
///
/// If a type has duplicate definitions in multiple namespace scopes or
/// definitions with different effective names, these are considered as
/// conflicting duplicate definitions and should not be reordered. This function
/// reports how many such types it finds.
///
/// @param eliminate whether to eliminate non-conflicting duplicates
///
/// @param report whether to report conflicting duplicate definitions
///
/// @param root the root XML element
///
/// @return the number of conflicting duplicate definitions
size_t handle_duplicate_types(bool eliminate, bool report, xmlNodePtr root)
{
  // map of type-id to pair of set of namespace scopes and vector of
  // xmlNodes
  std::unordered_map<
      std::string,
      std::pair<
          std::set<namespace_scope>,
          std::vector<xmlNodePtr>>> types;
  namespace_scope namespaces;

  // find all type occurrences
  std::function<void(xmlNodePtr)> dfs = [&](xmlNodePtr node) {
    if (node->type != XML_ELEMENT_NODE)
      return;
    const char* node_name = from_libxml(node->name);
    std::optional<std::string> namespace_name;
    if (strcmp(node_name, "namespace-decl") == 0)
      namespace_name = get_attribute(node, "name");
    if (namespace_name)
      namespaces.push_back(namespace_name.value());
    if (strcmp(node_name, "abi-corpus-group") == 0
        || strcmp(node_name, "abi-corpus") == 0
        || strcmp(node_name, "abi-instr") == 0
        || namespace_name)
      {
        for (auto child : get_children(node))
          dfs(child);
      }
    else
      {
        const auto id = get_attribute(node, "id");
        if (id)
          {
            auto& info = types[id.value()];
            info.first.insert(namespaces);
            info.second.push_back(node);
          }
      }
    if (namespace_name)
      namespaces.pop_back();
  };
  dfs(root);

  size_t conflicting_types = 0;
  for (const auto& [id, scopes_and_definitions] : types)
    {
      const auto& [scopes, definitions] = scopes_and_definitions;

      if (scopes.size() > 1)
        {
          if (report)
            std::cerr << "conflicting scopes found for type '" << id << "'\n";
          ++conflicting_types;
          continue;
        }

      const size_t count = definitions.size();
      if (count <= 1)
        continue;

      // Find a potentially maximal candidate by scanning through and
      // retaining the new definition if it's a supertree of the current
      // candidate.
      std::vector<bool> ok(count);
      size_t candidate = 0;
      ok[candidate] = true;
      for (size_t ix = 1; ix < count; ++ix)
        if (sub_tree(definitions[candidate], definitions[ix]))
          {
            candidate = ix;
            ok[candidate] = true;
          }

      // Verify the candidate is indeed maximal by scanning the
      // definitions not already known to be subtrees of it.
      bool bad = false;
      const auto& candidate_definition = definitions[candidate];
      const char* candidate_node_name = from_libxml(candidate_definition->name);
      const auto& candidate_effective_name =
          get_effective_name(candidate_definition);
      for (size_t ix = 0; ix < count; ++ix)
        {
          const auto& definition = definitions[ix];
          if (!ok[ix] && !sub_tree(definition, candidate_definition))
            {
              if (strcmp(from_libxml(definition->name), candidate_node_name) != 0
                  || get_effective_name(definition) != candidate_effective_name)
                ++conflicting_types;
              bad = true;
              break;
            }
        }

      if (bad)
        {
          if (report)
            std::cerr << "unresolvable duplicate definitions found for type '"
                      << id << "'\n";
          continue;
        }

      if (eliminate)
        // Remove all but the maximal definition.
        for (size_t ix = 0; ix < count; ++ix)
          if (ix != candidate)
            remove_element(definitions[ix]);
    }

  return conflicting_types;
}

static const std::set<std::string> INSTR_VARIABLE_ATTRIBUTES = {
  "path",
  "comp-dir-path",
  "language",
};

/// Collect elements of abi-instr elements by namespace.
///
/// Namespaces are not returned but are recursively traversed with the
/// namespace stack being maintained. Other elements are associated with
/// the current namespace.
///
/// @param nodes the nodes to traverse
///
/// @return child elements grouped by namespace scope
static std::map<namespace_scope, std::vector<xmlNodePtr>>
get_children_by_namespace(const std::vector<xmlNodePtr>& nodes)
{
  std::map<namespace_scope, std::vector<xmlNodePtr>> result;
  namespace_scope scope;

  std::function<void(xmlNodePtr)> process = [&](xmlNodePtr node) {
    if (node->type != XML_ELEMENT_NODE)
      return;
    std::optional<std::string> namespace_name;
    const char* node_name = from_libxml(node->name);
    if (strcmp(node_name, "namespace-decl") == 0)
      namespace_name = get_attribute(node, "name");
    if (namespace_name)
      {
        scope.push_back(namespace_name.value());
        for (auto child : get_children(node))
          process(child);
        scope.pop_back();
      }
    else
      result[scope].push_back(node);
  };

  for (auto node : nodes)
    for (auto child : get_children(node))
      process(child);
  return result;
}

/// Sort namespaces, types and declarations.
///
/// This loses annotations (XML comments) on namespace-decl elements.
/// It would have been a fair amount of extra work to preserve them.
///
/// @param root the XML root element
static void
sort_namespaces_types_and_declarations(xmlNodePtr root)
{
  // There are (currently) 2 ABI formats we handle here.
  //
  // 1. An abi-corpus containing one or more abi-instr. In this case, we
  // move all namespaces, types and declarations to a replacement
  // abi-instr at the end of the abi-corpus. The existing abi-instr will
  // then be confirmed as empty and removed.
  //
  // 2. An abi-corpus-group containing one or more abi-corpus each
  // containing zero or more abi-instr (with at least one abi-instr
  // altogether). In this case the replacement abi-instr is created
  // within the first abi-corpus of the group.
  //
  // Anything else is left alone. For example, single abi-instr elements
  // are present in some libabigail test suite files.

  // We first need to identify where to place the new abi-instr and
  // collect all the abi-instr to process.
  xmlNodePtr where = nullptr;
  std::vector<xmlNodePtr> instrs;

  auto process_corpus = [&](xmlNodePtr corpus) {
    if (!where)
      where = corpus;
    for (auto instr : get_children(corpus))
      if (strcmp(from_libxml(instr->name), "abi-instr") == 0)
        instrs.push_back(instr);
  };

  const char* root_name = from_libxml(root->name);
  if (strcmp(root_name, "abi-corpus-group") == 0)
    {
      // Process all corpora in a corpus group together.
      for (auto corpus : get_children(root))
        if (strcmp(from_libxml(corpus->name), "abi-corpus") == 0)
          process_corpus(corpus);
    }
  else if (strcmp(root_name, "abi-corpus") == 0)
    {
      // We have a corpus to sort, just get its instrs.
      process_corpus(root);
    }

  if (instrs.empty())
    return;

  // Collect the attributes of all the instrs.
  std::map<std::string, std::set<std::string>> attributes;
  for (auto instr : instrs)
    for (auto p = instr->properties; p; p = p->next)
      {
        // This is horrible. There should be a better way of iterating.
        const char* attribute_name = from_libxml(p->name);
        const auto attribute_value = get_attribute(instr, attribute_name);
        assert(attribute_value);
        attributes[attribute_name].insert(attribute_value.value());
      }

  // Create and attach a replacement instr and populate its attributes.
  xmlNodePtr replacement =
      xmlAddChild(where, xmlNewNode(nullptr, to_libxml("abi-instr")));
  for (const auto& attribute : attributes)
    {
      const char* attribute_name = attribute.first.c_str();
      const auto& attribute_values = attribute.second;
      if (attribute_values.size() == 1)
        set_attribute(replacement, attribute_name, *attribute_values.begin());
      else if (INSTR_VARIABLE_ATTRIBUTES.count(attribute_name))
        set_attribute(replacement, attribute_name, "various");
      else
        {
          std::cerr << "unexpectedly variable abi-instr attribute '"
                    << attribute_name << "'\n";
          remove_node(replacement);
          return;
        }
    }

  // Order XML nodes by XML element names, effective names, mangled names and
  // type ids.
  struct Compare {
    int
    cmp(xmlNodePtr a, xmlNodePtr b) const
    {
      int result;

      // Compare XML element names.
      result = strcmp(from_libxml(a->name), from_libxml(b->name));
      if (result)
          return result;

      // Compare effective names.
      const auto a_effective_name = get_effective_name(a);
      const auto b_effective_name = get_effective_name(b);

      result = compare_optional(a_effective_name, b_effective_name);
      if (result)
        return result;

      // Compare declarations using mangled names.
      result = compare_attributes("mangled-name", a, b);
      if (result)
        return result;

      // Compare types using ids.
      return compare_attributes("id", a, b);
    }

    bool
    operator()(xmlNodePtr a, xmlNodePtr b) const
    {
      return cmp(a, b) < 0;
    }
  };

  // Collect the child elements of all the instrs, by namespace scope.
  auto scoped_children = get_children_by_namespace(instrs);
  for (auto& [scope, children] : scoped_children)
    // Sort the children, preserving order of duplicates.
    std::stable_sort(children.begin(), children.end(), Compare());

  // Create namespace elements on demand. The global namespace, with
  // empty scope, is just the replacement instr itself.
  std::map<namespace_scope, xmlNodePtr> namespace_elements{{{}, replacement}};
  std::function<xmlNodePtr(const namespace_scope&)> get_namespace_element =
      [&](const namespace_scope& scope) {
        auto insertion = namespace_elements.insert({scope, nullptr});
    if (insertion.second)
      {
        // Insertion succeeded, so the scope cannot be empty.
        namespace_scope truncated = scope;
        truncated.pop_back();
        xmlNodePtr parent = get_namespace_element(truncated);
        // We can now create an XML element in the right place.
        xmlNodePtr child = xmlNewNode(nullptr, to_libxml("namespace-decl"));
        set_attribute(child, "name", scope.back());
        xmlAddChild(parent, child);
        insertion.first->second = child;
      }
    return insertion.first->second;
  };

  // Move the children to the replacement instr or its subelements.
  for (const auto& [scope, elements] : scoped_children)
    {
      xmlNodePtr namespace_element = get_namespace_element(scope);
      for (auto element : elements)
        move_element(element, namespace_element);
    }

  // Check the original instrs are now all empty and remove them.
  for (auto instr : instrs)
    if (get_children_by_namespace({instr}).empty())
      remove_node(instr);
    else
      std::cerr << "original abi-instr has residual child elements\n";
}

static constexpr std::array<std::string_view, 2> SYMBOL_SECTION_SUFFICES = {
  "symbol_list",
  "whitelist",
};

/// Read symbols from a file.
///
/// This aims to be compatible with the .ini format used by libabigail
/// for suppression specifications and symbol lists. All symbol list
/// sections in the given file are combined into a single set of
/// symbols.
///
/// @param filename the name of the file from which to read
///
/// @return a set of symbols
symbol_set
read_symbols(const char* filename)
{
  symbol_set symbols;
  std::ifstream file(filename);
  if (!file)
    {
      std::cerr << "error opening symbol file '" << filename << "'\n";
      exit(1);
    }

  bool in_symbol_section = false;
  std::string line;
  while (std::getline(file, line))
    {
      size_t start = 0;
      size_t limit = line.size();
      // Strip comments and leading / trailing whitespace.
      while (start < limit)
        {
          if (std::isspace(line[start]))
            ++start;
          else if (line[start] == '#')
            start = limit;
          else
            break;
        }
      while (start < limit)
        {
          if (std::isspace(line[limit - 1]))
            --limit;
          else
            break;
        }
      // Skip empty lines.
      if (start == limit)
        continue;
      // See if we are entering a symbol list section.
      if (line[start] == '[' && line[limit - 1] == ']')
        {
          std::string_view section(&line[start + 1], limit - start - 2);
          bool found = false;
          for (const auto& suffix : SYMBOL_SECTION_SUFFICES)
            if (section.size() >= suffix.size()
                && section.substr(section.size() - suffix.size()) == suffix)
              {
                found = true;
                break;
              }
          in_symbol_section = found;
          continue;
        }
      // Add symbol.
      if (in_symbol_section)
        symbols.insert(std::string(&line[start], limit - start));
    }
  if (!file.eof())
    {
      std::cerr << "error reading symbol file '" << filename << "'\n";
      exit(1);
    }
  return symbols;
}

/// Get aliases from XML node.
///
/// @param node the XML node to process
///
/// @return an ordered set of aliases
std::set<std::string>
get_aliases(xmlNodePtr node)
{
  std::set<std::string> aliases;
  const auto alias = get_attribute(node, "alias");
  if (alias)
    {
      std::istringstream is(alias.value());
      std::string item;
      while (std::getline(is, item, ','))
        aliases.insert(item);
    }
  return aliases;
}

/// Set aliases in XML node.
///
/// @param node the XML node to process
///
/// @param aliases an ordered set of aliases
void
set_aliases(xmlNodePtr node, const std::set<std::string>& aliases)
{
  if (aliases.empty())
    {
      unset_attribute(node, "alias");
    }
  else
    {
      std::ostringstream os;
      bool first = true;
      for (const auto& alias : aliases)
        {
          if (first)
            first = false;
          else
            os << ',';
          os << alias;
        }
      set_attribute(node, "alias", os.str());
    }
}

/// Gather information about symbols and record alias <-> main mappings.
///
/// @param symbol_map a map from elf-symbol-id to XML node
///
/// @param alias_map a map from alias elf-symbol-id to main
///
/// @param main_map a map from main elf-symbol-id to aliases
///
/// @param node the XML node to process
void
process_symbols(
    std::unordered_map<std::string, xmlNodePtr>& symbol_map,
    std::unordered_map<std::string, std::string>& alias_map,
    std::unordered_map<std::string, std::set<std::string>>& main_map,
    xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;
  const char* node_name = from_libxml(node->name);
  if (strcmp(node_name, "abi-corpus-group") == 0
      || strcmp(node_name, "abi-corpus") == 0
      || strcmp(node_name, "elf-variable-symbols") == 0
      || strcmp(node_name, "elf-function-symbols") == 0)
    {
      // Process children.
      for (auto child : get_children(node))
        process_symbols(symbol_map, alias_map, main_map, child);
    }
  else if (strcmp(node_name, "elf-symbol") == 0)
    {
      const auto id = get_elf_symbol_id(node);
      if (!symbol_map.insert({id, node}).second)
        {
          std::cerr << "multiple symbols with id " << id << "\n";
          exit(1);
        }
      const auto aliases = get_aliases(node);
      for (const auto& alias : aliases)
        if (!alias_map.insert({alias, id}).second)
          {
            std::cerr << "multiple aliases with id " << alias << "\n";
            exit(1);
          }
      if (!aliases.empty())
        main_map.insert({id, aliases});
    }
}

/// Rewrite elf-symbol-id attributes following ELF symbol removal.
///
/// @param mapping map from old to new elf-symbol-id, if any
void
rewrite_symbols_in_declarations(
    const std::unordered_map<std::string, std::optional<std::string>>& mapping,
    xmlNodePtr node)
{
  if (node->type != XML_ELEMENT_NODE)
    return;

  const char* node_name = from_libxml(node->name);
  if (strcmp(node_name, "var-decl") == 0
      || strcmp(node_name, "function-decl") == 0)
    {
      auto symbol = get_attribute(node, "elf-symbol-id");
      bool changed = false;
      while (symbol)
        {
          const auto it = mapping.find(symbol.value());
          if (it == mapping.end())
            break;
          symbol = it->second;
          changed = true;
        }
      if (changed)
        {
          if (symbol)
            set_attribute(node, "elf-symbol-id", symbol.value());
          else
            unset_attribute(node, "elf-symbol-id");
        }
    }

  for (xmlNodePtr child : get_children(node))
    rewrite_symbols_in_declarations(mapping, child);
}

/// Remove unlisted ELF symbols.
///
/// @param symbols the set of symbols
///
/// @param root the XML root element
///
/// @return mapping from alias to main elf-symbol-id
std::unordered_map<std::string, std::string>
    filter_symbols(const std::optional<symbol_set>& symbols, xmlNodePtr root)
{
  // find symbols and record alias <-> main mappings
  std::unordered_map<std::string, xmlNodePtr> symbol_map;
  std::unordered_map<std::string, std::string> alias_map;
  std::unordered_map<std::string, std::set<std::string>> main_map;
  process_symbols(symbol_map, alias_map, main_map, root);
  // check that aliases and main symbols are disjoint
  for (const auto& [alias, main] : alias_map)
    if (alias_map.count(main))
      {
        std::cerr << "found main symbol and alias with id " << main << '\n';
        exit(1);
      }

  if (!symbols)
    return alias_map;

  // Track when an alias is promoted to a main symbol or a symbol is deleted as
  // these are the cases when we need update references to symbols in
  // declarations.
  std::unordered_map<std::string, std::optional<std::string>> mapping;

  // filter the symbols, preserving those listed
  for (const auto& [id, node] : symbol_map)
    {
      const auto name = get_attribute(node, "name");
      assert(name);
      if (symbols->count(name.value()))
        continue;
      remove_element(node);

      // The symbol has been removed, so remove its id from the alias <-> main
      // mappings, promoting another alias to main symbol if needed, and
      // updating XML alias attributes.
      //
      // There are 3 cases:
      //   a main symbol - with one or more aliases
      //   an alias - with a main symbol
      //   an unaliased symbol
      if (const auto main_it = main_map.find(id);
          main_it != main_map.end())
        {
          // A main symbol with one or more aliases.
          std::set<std::string> aliases;
          std::swap(aliases, main_it->second);
          main_map.erase(main_it);
          // the first alias will be the new main symbol
          const auto first_it = aliases.begin();
          assert(first_it != aliases.end());
          const auto first = *first_it;
          // remove first from the list of aliases and its link to id
          aliases.erase(first_it);
          alias_map.erase(first);
          if (!aliases.empty())
            {
              // update the XML attribute
              set_aliases(symbol_map[first], aliases);
              // update the maps
              for (const auto& alias : aliases)
                alias_map[alias] = first;
              std::swap(aliases, main_map[first]);
            }
          // declarations referring to id must be repointed at first
          mapping[id] = {first};
        }
      else if (const auto alias_it = alias_map.find(id);
               alias_it != alias_map.end())
        {
          // An alias with a main symbol.
          const auto main = alias_it->second;
          auto& aliases = main_map[main];
          // remove id from the maps
          alias_map.erase(alias_it);
          aliases.erase(id);
          // update the XML attribute
          set_aliases(symbol_map[main], aliases);
          if (aliases.empty())
            // main hasn't changed but is no longer aliased
            main_map.erase(main);
        }
      else
        {
          // An unaliased symbol.
          //
          // declaration references to id must be removed
          mapping[id] = {};
        }
    }

  rewrite_symbols_in_declarations(mapping, root);

  return alias_map;
}

/// Main program.
///
/// Read and write ABI XML, with optional processing passes.
///
/// @param argc argument count
///
/// @param argv argument vector
///
/// @return exit status
int
main(int argc, char* argv[])
{
  // Defaults.
  const char* opt_input = nullptr;
  const char* opt_output = nullptr;
  std::optional<symbol_set> opt_symbols;
  LocationInfo opt_locations = LocationInfo::COLUMN;
  int opt_indentation = 2;
  bool opt_normalise_anonymous = false;
  bool opt_reanonymise_anonymous = false;
  bool opt_discard_naming_typedefs = false;
  bool opt_remove_function_parameter_type_qualifiers = false;
  bool opt_prune_unreachable = false;
  bool opt_report_untyped = false;
  bool opt_abort_on_untyped = false;
  bool opt_clear_non_reachable = false;
  bool opt_eliminate_excess_members = false;
  bool opt_eliminate_duplicates = false;
  bool opt_report_conflicts = false;
  bool opt_sort = false;
  bool opt_drop_empty = false;

  // Experimental flags. These are not part of --all.
  //
  // TODO: Move out of experimental status when stable.
  bool opt_renumber_anonymous_types = false;

  // Process command line.
  auto usage = [&]() -> int {
    std::cerr << "usage: " << argv[0] << '\n'
              << "  [-i|--input file]\n"
              << "  [-o|--output file]\n"
              << "  [-S|--symbols file]\n"
              << "  [-L|--locations {column|line|file|none}]\n"
              << "  [-I|--indentation n]\n"
              << "  [-a|--all] (implies -n -r -t -f -p -u -b -x -e -c -s -d)\n"
              << "  [-n|--[no-]normalise-anonymous]\n"
              << "  [-r|--[no-]reanonymise-anonymous]\n"
              << "  [-t|--[no-]discard-naming-typedefs]\n"
              << "  [-f|--[no-]remove-function-parameter-type-qualifiers]\n"
              << "  [-p|--[no-]prune-unreachable]\n"
              << "  [-u|--[no-]report-untyped]\n"
              << "  [-U|--abort-on-untyped-symbols]\n"
              << "  [-b|--[no-]clear-non-reachable]\n"
              << "  [-x|--[no-]eliminate-excess-members\n"
              << "  [-e|--[no-]eliminate-duplicates]\n"
              << "  [-c|--[no-]report-conflicts]\n"
              << "  [-s|--[no-]sort]\n"
              << "  [-d|--[no-]drop-empty]\n"
              << "\nExperimental flags, not part of --all\n"
              << "  [-M|--[no-]renumber-anonymous-types]\n";
    return 1;
  };
  int opt_index = 1;
  auto get_arg = [&]() {
    if (opt_index < argc)
      return argv[opt_index++];
    exit(usage());
  };
  while (opt_index < argc)
    {
      const std::string arg = get_arg();
      if (arg == "-i" || arg == "--input")
        opt_input = get_arg();
      else if (arg == "-o" || arg == "--output")
        opt_output = get_arg();
      else if (arg == "-S" || arg == "--symbols")
        opt_symbols = read_symbols(get_arg());
      else if (arg == "-L" || arg == "--locations")
        {
          auto it = LOCATION_INFO_NAME.find(get_arg());
          if (it == LOCATION_INFO_NAME.end())
            exit(usage());
          opt_locations = it->second;
        }
      else if (arg == "-I" || arg == "--indentation")
        {
          std::istringstream is(get_arg());
          is >> std::noskipws >> opt_indentation;
          if (!is || !is.eof() || opt_indentation < 0)
            exit(usage());
        }
      else if (arg == "-a" || arg == "--all")
        opt_normalise_anonymous = opt_reanonymise_anonymous
                                = opt_discard_naming_typedefs
                                = opt_remove_function_parameter_type_qualifiers
                                = opt_prune_unreachable
                                = opt_report_untyped
                                = opt_clear_non_reachable
                                = opt_eliminate_excess_members
                                = opt_eliminate_duplicates
                                = opt_report_conflicts
                                = opt_sort
                                = opt_drop_empty
                                = true;
      else if (arg == "-n" || arg == "--normalise-anonymous")
        opt_normalise_anonymous = true;
      else if (arg == "--no-normalise-anonymous")
        opt_normalise_anonymous = false;
      else if (arg == "-r" || arg == "--reanonymise-anonymous")
        opt_reanonymise_anonymous = true;
      else if (arg == "--no-reanonymise-anonymous")
        opt_reanonymise_anonymous = false;
      else if (arg == "-t" || arg == "--discard-naming-typedefs")
        opt_discard_naming_typedefs = true;
      else if (arg == "--no-discard-naming-typedefs")
        opt_discard_naming_typedefs = false;
      else if (arg == "-f" ||
               arg == "--remove-function-parameter-type-qualifiers")
        opt_remove_function_parameter_type_qualifiers = true;
      else if (arg == "--no-remove-function-parameter-type-qualifiers")
        opt_remove_function_parameter_type_qualifiers = false;
      else if (arg == "-p" || arg == "--prune-unreachable")
        opt_prune_unreachable = true;
      else if (arg == "--no-prune-unreachable")
        opt_prune_unreachable = false;
      else if (arg == "-u" || arg == "--report-untyped")
        opt_report_untyped = true;
      else if (arg == "--no-report-untyped")
        opt_report_untyped = false;
      else if (arg == "-U" || arg == "--abort-on-untyped-symbols")
        opt_abort_on_untyped = true;
      else if (arg == "-b" || arg == "--clear-non-reachable")
        opt_clear_non_reachable = true;
      else if (arg == "--no-clear-non-reachable")
        opt_clear_non_reachable = false;
      else if (arg == "-x" || arg == "--eliminate-excess-members")
        opt_eliminate_excess_members = true;
      else if (arg == "--no-eliminate-excess-members")
        opt_eliminate_excess_members = false;
      else if (arg == "-e" || arg == "--eliminate-duplicates")
        opt_eliminate_duplicates = true;
      else if (arg == "--no-eliminate-duplicates")
        opt_eliminate_duplicates = false;
      else if (arg == "-c" || arg == "--report-conflicts")
        opt_report_conflicts = true;
      else if (arg == "--no-report-conflicts")
        opt_report_conflicts = false;
      else if (arg == "-s" || arg == "--sort")
        opt_sort = true;
      else if (arg == "--no-sort")
        opt_sort = false;
      else if (arg == "-d" || arg == "--drop-empty")
        opt_drop_empty = true;
      else if (arg == "--no-drop-empty")
        opt_drop_empty = false;
      else if (arg == "-M" || arg == "--renumber-anonymous-types")
        opt_renumber_anonymous_types = true;
      else if (arg == "--no-renumber-anonymous-types")
        opt_renumber_anonymous_types = false;
      else
        exit(usage());
    }

  // Open input for reading.
  int in_fd = STDIN_FILENO;
  if (opt_input)
    {
      in_fd = open(opt_input, O_RDONLY);
      if (in_fd < 0)
        {
          std::cerr << "could not open '" << opt_input << "' for reading: "
                    << strerror(errno) << '\n';
          exit(1);
        }
    }

  // Read the XML.
  xmlParserCtxtPtr parser_context = xmlNewParserCtxt();
  xmlDocPtr document
      = xmlCtxtReadFd(parser_context, in_fd, nullptr, nullptr, 0);
  if (!document)
    {
      std::cerr << "failed to parse input as XML\n";
      exit(1);
    }
  xmlFreeParserCtxt(parser_context);
  close(in_fd);

  // Get the root element.
  xmlNodePtr root = xmlDocGetRootElement(document);
  if (!root)
    {
      std::cerr << "XML document has no root element\n";
      exit(1);
    }

  // Strip text nodes to simplify other operations.
  strip_text(root);

  // Get alias -> main mapping and remove unlisted symbols.
  const auto alias_map = filter_symbols(opt_symbols, root);

  // Record type ids which correspond to anonymous types.
  // Renumber recorded type ids using information about the type.
  // Replace recorded type ids by renumbered ones.
  if (opt_renumber_anonymous_types)
    {
      std::unordered_map<std::string, xmlNodePtr> to_renumber;
      std::unordered_set<size_t> used_hashes;
      record_ids_to_renumber(root, to_renumber, used_hashes);

      std::unordered_map<std::string, std::string> type_id_map;
      for (const auto& [type_id, node] : to_renumber)
        resolve_ids_to_renumber(type_id, to_renumber, used_hashes, type_id_map);

      renumber_type_ids(root, type_id_map);
    }

  // Normalise anonymous type names.
  // Reanonymise anonymous types.
  // Discard naming typedef backlinks.
  if (opt_normalise_anonymous || opt_reanonymise_anonymous
      || opt_discard_naming_typedefs)
    handle_anonymous_types(opt_normalise_anonymous, opt_reanonymise_anonymous,
                           opt_discard_naming_typedefs, root);

  // Remove useless top-level qualifiers on function parameter and return
  // types.
  if (opt_remove_function_parameter_type_qualifiers)
    {
      std::unordered_map<std::string, std::string> qualifier_id_to_type_id;
      build_qualifier_id_to_type_id_map(root, qualifier_id_to_type_id);
      resolve_qualifier_chains(qualifier_id_to_type_id);
      remove_function_parameter_type_qualifiers(root, qualifier_id_to_type_id);
    }

  // Prune unreachable elements and/or report untyped symbols.
  size_t untyped_symbols = 0;
  if (opt_prune_unreachable || opt_report_untyped || opt_abort_on_untyped)
    untyped_symbols += handle_unreachable(
        opt_prune_unreachable, opt_report_untyped, alias_map, root);
  if (opt_abort_on_untyped && untyped_symbols)
    {
      std::cerr << "found " << untyped_symbols << " untyped symbols\n";
      exit(1);
    }

  // Limit location information.
  if (opt_locations > LocationInfo::COLUMN)
    limit_locations(opt_locations, root);

  // Clear unwanted non-reachable attributes.
  if (opt_clear_non_reachable)
    clear_non_reachable(root);

  // Handle excess data members.
  handle_excess_members(opt_eliminate_excess_members, root);

  // Eliminate complete duplicates and extra fragments of types.
  // Report conflicting duplicate defintions.
  // Record whether there are conflicting duplicate definitions.
  size_t conflicting_types = 0;
  if (opt_eliminate_duplicates || opt_report_conflicts || opt_sort)
    conflicting_types += handle_duplicate_types(
        opt_eliminate_duplicates, opt_report_conflicts, root);

  // Sort namespaces, types and declarations.
  if (opt_sort)
    {
      if (conflicting_types)
        std::cerr << "found type definition conflicts, skipping sort\n";
      else
        sort_namespaces_types_and_declarations(root);
    }

  // Drop empty subelements.
  if (opt_drop_empty)
    drop_empty(root);

  // Reformat root element for human consumption.
  format_xml(std::string(opt_indentation, ' '), std::string(), root);

  // Open output for writing.
  int out_fd = STDOUT_FILENO;
  if (opt_output)
    {
      out_fd = open(opt_output, O_CREAT | O_TRUNC | O_WRONLY,
                    S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP | S_IROTH | S_IWOTH);
      if (out_fd < 0)
        {
          std::cerr << "could not open '" << opt_output << "' for writing: "
                    << strerror(errno) << '\n';
          exit(1);
        }
    }

  // Write the XML.
  //
  // First to memory, as we need to do a little post-processing.
  xmlChar* out_data;
  int out_size;
  xmlDocDumpMemory(document, &out_data, &out_size);
  // Remove the XML declaration as it currently upsets abidiff.
  xmlChar* out_limit = out_data + out_size;
  while (out_data < out_limit && *out_data != '\n')
    ++out_data;
  if (out_data < out_limit)
    ++out_data;
  // Adjust quotes to match abidw.
  adjust_quotes(out_data, out_limit);
  // And now to a file.
  size_t count = out_limit - out_data;
  if (write(out_fd, out_data, count) != count)
    {
      std::cerr << "could not write output: " << strerror(errno) << '\n';
      exit(1);
    }
  if (close(out_fd) < 0)
    {
      std::cerr << "could not close output: " << strerror(errno) << '\n';
      exit(1);
    }

  // Free libxml document.
  xmlFreeDoc(document);
  return 0;
}
