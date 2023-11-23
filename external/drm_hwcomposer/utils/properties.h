#ifndef UTILS_PROPERTIES_H_
#define UTILS_PROPERTIES_H_

#ifdef ANDROID

#include <cutils/properties.h>

#else

#include <cstdio>
#include <cstdlib>
#include <cstring>

// NOLINTNEXTLINE(readability-identifier-naming)
constexpr int PROPERTY_VALUE_MAX = 92;

auto inline property_get(const char *name, char *value,
                         const char *default_value) -> int {
  char *prop = std::getenv(name);
  if (prop == nullptr) {
    snprintf(value, PROPERTY_VALUE_MAX, "%s", default_value);
  }
  return strlen(value);
}

#endif

#endif