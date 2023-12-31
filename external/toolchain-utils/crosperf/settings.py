# -*- coding: utf-8 -*-
# Copyright 2019 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Module to get the settings from experiment file."""


from cros_utils import logger
from cros_utils import misc
from download_images import ImageDownloader


class Settings(object):
    """Class representing settings (a set of fields) from an experiment file."""

    def __init__(self, name, settings_type):
        self.name = name
        self.settings_type = settings_type
        self.fields = {}
        self.parent = None

    def SetParentSettings(self, settings):
        """Set the parent settings which these settings can inherit from."""
        self.parent = settings

    def AddField(self, field):
        name = field.name
        if name in self.fields:
            raise SyntaxError("Field %s defined previously." % name)
        self.fields[name] = field

    def SetField(self, name, value, append=False):
        if name not in self.fields:
            raise SyntaxError(
                "'%s' is not a valid field in '%s' settings"
                % (name, self.settings_type)
            )
        if append:
            self.fields[name].Append(value)
        else:
            self.fields[name].Set(value)

    def GetField(self, name):
        """Get the value of a field with a given name."""
        if name not in self.fields:
            raise SyntaxError(
                "Field '%s' not a valid field in '%s' settings."
                % (name, self.name)
            )
        field = self.fields[name]
        if not field.assigned and field.required:
            raise SyntaxError(
                "Required field '%s' not defined in '%s' settings."
                % (name, self.name)
            )
        return self.fields[name].Get()

    def Inherit(self):
        """Inherit any unset values from the parent settings."""
        for name in self.fields:
            if (
                not self.fields[name].assigned
                and self.parent
                and name in self.parent.fields
                and self.parent.fields[name].assigned
            ):
                self.fields[name].Set(self.parent.GetField(name), parse=False)

    def Override(self, settings):
        """Override settings with settings from a different object."""
        for name in settings.fields:
            if name in self.fields and settings.fields[name].assigned:
                self.fields[name].Set(settings.GetField(name), parse=False)

    def Validate(self):
        """Check that all required fields have been set."""
        for name in self.fields:
            if not self.fields[name].assigned and self.fields[name].required:
                raise SyntaxError("Field %s is invalid." % name)

    def GetXbuddyPath(
        self,
        path_str,
        autotest_path,
        debug_path,
        board,
        chromeos_root,
        log_level,
        download_debug,
    ):
        prefix = "remote"
        l = logger.GetLogger()
        if (
            path_str.find("trybot") < 0
            and path_str.find("toolchain") < 0
            and path_str.find(board) < 0
            and path_str.find(board.replace("_", "-"))
        ):
            xbuddy_path = "%s/%s/%s" % (prefix, board, path_str)
        else:
            xbuddy_path = "%s/%s" % (prefix, path_str)
        image_downloader = ImageDownloader(l, log_level)
        # Returns three variables: image, autotest_path, debug_path
        return image_downloader.Run(
            misc.CanonicalizePath(chromeos_root),
            xbuddy_path,
            autotest_path,
            debug_path,
            download_debug,
        )
