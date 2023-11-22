#
# First step in looking for duplicate file GUIDs
#
# find . -name "*.inf" | \
#   xargs grep -H FILE_GUID | \
#   sed 's/^\(.*\):[ \t]*FILE_GUID[ \t]*=[ \t]*\([a-f.A-F.0-9.-]*\).*\r$/\2 \1/' | \
#   sort
#
