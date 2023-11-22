#!/usr/bin/env python3
#
# Copyright 2018 - The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""It is an AIDEGen sub task : IDE operation task!

Takes a project file path as input, after passing the needed check(file
existence, IDE type, etc.), launch the project in related IDE.

    Typical usage example:

    ide_util_obj = IdeUtil()
    if ide_util_obj.is_ide_installed():
        ide_util_obj.config_ide()
        ide_util_obj.launch_ide(project_file)
"""

import fnmatch
import glob
import logging
import os
import platform
import subprocess

from aidegen import constant
from aidegen.lib.config import AidegenConfig

# Add 'nohup' to prevent IDE from being terminated when console is terminated.
_NOHUP = 'nohup'
_IGNORE_STD_OUT_ERR_CMD = '2>/dev/null >&2'
_IDEA_FOLDER = '.idea'
_IML_EXTENSION = '.iml'
_JDK_PATH_TOKEN = '@JDKpath'
_TARGET_JDK_NAME_TAG = '<name value="JDK18" />'
_COMPONENT_END_TAG = '  </component>'


class IdeUtil():
    """Provide a set of IDE operations, e.g., launch and configuration.

    Attributes:
        _ide: IdeBase derived instance, the related IDE object.

    For example:
        1. Check if IDE is installed.
        2. Launch an IDE.
        3. Config IDE, e.g. config code style, SDK path, and etc.
    """

    def __init__(self,
                 installed_path=None,
                 ide='j',
                 config_reset=False,
                 is_mac=False):
        logging.debug('IdeUtil with OS name: %s%s', platform.system(),
                      '(Mac)' if is_mac else '')
        self._ide = _get_ide(installed_path, ide, config_reset, is_mac)

    def is_ide_installed(self):
        """Checks if the IDE is already installed.

        Returns:
            True if IDE is installed already, otherwise False.
        """
        return self._ide.is_ide_installed()

    def launch_ide(self, project_file):
        """Launches the relative IDE by opening the passed project file.

        Args:
            project_file: The full path of the IDE project file.
        """
        return self._ide.launch_ide(project_file)

    def config_ide(self):
        """To config the IDE, e.g., setup code style, init SDK, and etc."""
        if self.is_ide_installed() and self._ide:
            self._ide.apply_optional_config()

    def get_default_path(self):
        """Gets IDE default installed path."""
        return self._ide.default_installed_path

    def ide_name(self):
        """Gets IDE name."""
        return self._ide.ide_name


class IdeBase():
    """The most base class of IDE, provides interface and partial path init.

    Attributes:
        _installed_path: String for the IDE binary path.
        _config_reset: Boolean, True for reset configuration, else not reset.
        _bin_file_name: String for IDE executable file name.
        _bin_paths: A list of all possible IDE executable file absolute paths.
        _ide_name: String for IDE name.
        _bin_folders: A list of all possible IDE installed paths.

    For example:
        1. Check if IDE is installed.
        2. Launch IDE.
        3. Config IDE.
    """

    def __init__(self, installed_path=None, config_reset=False):
        self._installed_path = installed_path
        self._config_reset = config_reset
        self._ide_name = ''
        self._bin_file_name = ''
        self._bin_paths = []
        self._bin_folders = []

    def is_ide_installed(self):
        """Checks if IDE is already installed.

        Returns:
            True if IDE is installed already, otherwise False.
        """
        return bool(self._installed_path)

    def launch_ide(self, project_file):
        """Launches IDE by opening the passed project file.

        Args:
            project_file: The full path of the IDE's project file.
        """
        _launch_ide(project_file, self._get_ide_cmd(project_file),
                    self._ide_name)

    def apply_optional_config(self):
        """Handles IDE relevant configs."""
        # Default does nothing, the derived classes know what need to config.

    @property
    def default_installed_path(self):
        """Gets IDE default installed path."""
        return ' '.join(self._bin_folders)

    @property
    def ide_name(self):
        """Gets IDE name."""
        return self._ide_name

    def _get_ide_cmd(self, project_file):
        """Compose launch IDE command to run a new process and redirect output.

        Args:
            project_file: The full path of the IDE's project file.

        Returns:
            A string of launch IDE command.
        """
        return _get_run_ide_cmd(self._installed_path, project_file)

    def _init_installed_path(self, installed_path):
        """Initialize IDE installed path.

        Args:
            installed_path: the installed path to be checked.
        """
        if installed_path:
            self._installed_path = _get_script_from_input_path(
                installed_path, self._bin_file_name)
        else:
            self._installed_path = self._get_script_from_system()

    def _get_script_from_system(self):
        """Get correct IDE installed path from internal path.

        Returns:
            The sh full path, or None if no IntelliJ version is installed.
        """
        return _get_script_from_internal_path(self._bin_paths, self._ide_name)

    def _get_possible_bin_paths(self):
        """Gets all possible IDE installed paths."""
        return [os.path.join(f, self._bin_file_name) for f in self._bin_folders]


class IdeIntelliJ(IdeBase):
    """Provide basic IntelliJ ops, e.g., launch IDEA, and config IntelliJ.

    Class Attributes:
        _JDK_PATH: The path of JDK in android project.
        _IDE_JDK_TABLE_PATH: The path of JDK table which record JDK info in IDE.
        _JDK_PART_TEMPLATE_PATH: The path of the template of partial JDK table.
        _JDK_FULL_TEMPLATE_PATH: The path of the template of full JDK table.

    For example:
        1. Check if IntelliJ is installed.
        2. Launch an IntelliJ.
        3. Config IntelliJ.
    """

    _JDK_PATH = ''
    _IDE_JDK_TABLE_PATH = ''
    _JDK_PART_TEMPLATE_PATH = ''
    _JDK_FULL_TEMPLATE_PATH = ''

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._ide_name = constant.IDE_INTELLIJ
        self._ls_ce_path = ''
        self._ls_ue_path = ''
        self._init_installed_path(installed_path)

    def apply_optional_config(self):
        """Do IDEA global config action.

        Run code style config, SDK config.
        """
        if not self._installed_path:
            return
        # Skip config action if there's no config folder exists.
        _path_list = self._get_config_root_paths()
        if not _path_list:
            return

        for _config_path in _path_list:
            self._set_jdk_config(_config_path)

    def _get_config_root_paths(self):
        """Get the config root paths from derived class.

        Returns:
            A string list of IDE config paths, return multiple paths if more
            than one path are found, return None if no path is found.
        """
        raise NotImplementedError()

    def _get_config_folder_name(self):
        """Get the config sub folder name from derived class.

        Returns:
            A string of the sub path for the config folder.
        """
        raise NotImplementedError('Method overriding is needed.')

    def _set_jdk_config(self, path):
        """Add jdk path to jdk.table.xml

        Args:
            path: The path of IntelliJ config path.
        """
        jdk_table_path = os.path.join(path, self._IDE_JDK_TABLE_PATH)
        try:
            if os.path.isfile(jdk_table_path):
                with open(jdk_table_path, 'r+') as jdk_table_fd:
                    jdk_table = jdk_table_fd.read()
                    jdk_table_fd.seek(0)
                    if _TARGET_JDK_NAME_TAG not in jdk_table:
                        with open(self._JDK_PART_TEMPLATE_PATH) as template_fd:
                            template = template_fd.read()
                            template = template.replace(_JDK_PATH_TOKEN,
                                                        self._JDK_PATH)
                            jdk_table = jdk_table.replace(
                                _COMPONENT_END_TAG, template)
                            jdk_table_fd.truncate()
                            jdk_table_fd.write(jdk_table)
            else:
                with open(self._JDK_FULL_TEMPLATE_PATH) as template_fd:
                    template = template_fd.read()
                    template = template.replace(_JDK_PATH_TOKEN, self._JDK_PATH)
                    with open(jdk_table_path, 'w') as jdk_table_fd:
                        jdk_table_fd.write(template)
        except IOError as err:
            logging.warning(err)

    def _get_preferred_version(self):
        """Get users' preferred IntelliJ version.

        Locates the IntelliJ IDEA launch script path by following rule.

        1. If config file recorded user's preference version, load it.
        2. If config file didn't record, search them form default path if there
           are more than one version, ask user and record it.

        Returns:
            The sh full path, or None if no IntelliJ version is installed.
        """
        cefiles = _get_intellij_version_path(self._ls_ce_path)
        uefiles = _get_intellij_version_path(self._ls_ue_path)
        all_versions = self._get_all_versions(cefiles, uefiles)
        if len(all_versions) > 1:
            with AidegenConfig() as aconf:
                if not self._config_reset and (
                        aconf.preferred_version in all_versions):
                    return aconf.preferred_version
                preferred = _ask_preference(all_versions)
                if preferred:
                    aconf.preferred_version = preferred
                return preferred
        elif all_versions:
            return all_versions[0]
        return None

    def _get_script_from_system(self):
        """Get correct IntelliJ installed path from internal path.

        Returns:
            The sh full path, or None if no IntelliJ version is installed.
        """
        found = self._get_preferred_version()
        if found:
            logging.debug('IDE internal installed path: %s.', found)
        return found

    @staticmethod
    def _get_all_versions(cefiles, uefiles):
        """Get all versions of launch script files.

        Args:
            cefiles: CE version launch script paths.
            uefiles: UE version launch script paths.

        Returns:
            A list contains all versions of launch script files.
        """
        all_versions = []
        if cefiles:
            all_versions.extend(cefiles)
        if uefiles:
            all_versions.extend(uefiles)
        return all_versions

    @staticmethod
    def _get_code_style_config():
        """Get Android build-in IntelliJ code style config file.

        Returns:
            None if the file is not found, otherwise a full path string of
            Intellij Android code style file.
        """
        _config_source = os.path.join(constant.ANDROID_ROOT_PATH, 'development',
                                      'ide', 'intellij', 'codestyles',
                                      'AndroidStyle.xml')

        return _config_source if os.path.isfile(_config_source) else None


class IdeLinuxIntelliJ(IdeIntelliJ):
    """Provide the IDEA behavior implementation for OS Linux.

    For example:
        1. Check if IntelliJ is installed.
        2. Launch an IntelliJ.
        3. Config IntelliJ.
    """

    _JDK_PATH = os.path.join(constant.ANDROID_ROOT_PATH,
                             'prebuilts/jdk/jdk8/linux-x86')
    # TODO(b/127899277): Preserve a config for jdk version option case.
    _IDE_JDK_TABLE_PATH = 'config/options/jdk.table.xml'
    _JDK_PART_TEMPLATE_PATH = os.path.join(
        constant.AIDEGEN_ROOT_PATH, 'templates/jdkTable/part.jdk.table.xml')
    _JDK_FULL_TEMPLATE_PATH = os.path.join(constant.AIDEGEN_ROOT_PATH,
                                           'templates/jdkTable/jdk.table.xml')

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_file_name = 'idea.sh'
        self._bin_folders = ['/opt/intellij-*/bin']
        self._ls_ce_path = os.path.join('/opt/intellij-ce-2*/bin',
                                        self._bin_file_name)
        self._ls_ue_path = os.path.join('/opt/intellij-ue-2*/bin',
                                        self._bin_file_name)
        self._init_installed_path(installed_path)

    def _get_config_root_paths(self):
        """To collect the global config folder paths of IDEA as a string list.

        The config folder of IntelliJ IDEA is under the user's home directory,
        .IdeaIC20xx.x and .IntelliJIdea20xx.x are folder names for different
        versions.

        Returns:
            A string list for IDE config root paths, and return None for failed
            to found case.
        """
        if not self._installed_path:
            return None

        _config_folders = []
        _config_folder = ''
        # TODO(b/123459239): For the case that the user provides the IDEA
        # binary path, we now collect all possible IDEA config root paths.
        if 'e-20' not in self._installed_path:
            _config_folders = glob.glob(
                os.path.join(os.getenv('HOME'), '.IdeaI?20*'))
            _config_folders.extend(
                glob.glob(os.path.join(os.getenv('HOME'), '.IntelliJIdea20*')))
            logging.info('The config path list: %s.\n', _config_folders)
        else:
            _path_data = self._installed_path.split('-')
            _ide_version = _path_data[2].split(os.sep)[0]
            if _path_data[1] == 'ce':
                _config_folder = ''.join(['.IdeaIC', _ide_version])
            else:
                _config_folder = ''.join(['.IntelliJIdea', _ide_version])

            _config_folders.append(
                os.path.join(os.getenv('HOME'), _config_folder))
        return _config_folders

    def _get_config_folder_name(self):
        """A interface used to provide the config sub folder name.

        Returns:
            A sub path string of the config folder.
        """
        return os.path.join('config', 'codestyles')


class IdeMacIntelliJ(IdeIntelliJ):
    """Provide the IDEA behavior implementation for OS Mac.

    For example:
        1. Check if IntelliJ is installed.
        2. Launch an IntelliJ.
        3. Config IntelliJ.
    """

    _JDK_PATH = os.path.join(constant.ANDROID_ROOT_PATH,
                             'prebuilts/jdk/jdk8/darwin-x86')
    _IDE_JDK_TABLE_PATH = 'options/jdk.table.xml'
    _JDK_PART_TEMPLATE_PATH = os.path.join(
        constant.AIDEGEN_ROOT_PATH, 'templates/jdkTable/part.mac.jdk.table.xml')
    _JDK_FULL_TEMPLATE_PATH = os.path.join(
        constant.AIDEGEN_ROOT_PATH, 'templates/jdkTable/mac.jdk.table.xml')

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_file_name = 'idea'
        self._bin_folders = ['/Applications/IntelliJ IDEA.app/Contents/MacOS']
        self._bin_paths = self._get_possible_bin_paths()
        self._ls_ce_path = os.path.join(
            '/Applications/IntelliJ IDEA CE.app/Contents/MacOS',
            self._bin_file_name)
        self._ls_ue_path = os.path.join(
            '/Applications/IntelliJ IDEA.app/Contents/MacOS',
            self._bin_file_name)
        self._init_installed_path(installed_path)

    def _get_config_root_paths(self):
        """To collect the global config folder paths of IDEA as a string list.

        Returns:
            A string list for IDE config root paths, and return None for failed
            to found case.
        """
        if not self._installed_path:
            return None

        _config_folders = []
        if 'IntelliJ' in self._installed_path:
            _config_folders = glob.glob(
                os.path.join(
                    os.getenv('HOME'), 'Library/Preferences/IdeaI?20*'))
            _config_folders.extend(
                glob.glob(
                    os.path.join(
                        os.getenv('HOME'),
                        'Library/Preferences/IntelliJIdea20*')))
        return _config_folders

    def _get_config_folder_name(self):
        """A interface used to provide the config sub folder name.

        Returns:
            A sub path string of the config folder.
        """
        return 'codeStyles'


class IdeStudio(IdeBase):
    """Class offers a set of Android Studio launching utilities.

    For example:
        1. Check if Android Studio is installed.
        2. Launch an Android Studio.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._ide_name = constant.IDE_ANDROID_STUDIO


class IdeLinuxStudio(IdeStudio):
    """Class offers a set of Android Studio launching utilities for OS Linux.

    For example:
        1. Check if Android Studio is installed.
        2. Launch an Android Studio.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_file_name = 'studio.sh'
        self._bin_folders = ['/opt/android-*/bin']
        self._bin_paths = self._get_possible_bin_paths()
        self._init_installed_path(installed_path)


class IdeMacStudio(IdeStudio):
    """Class offers a set of Android Studio launching utilities for OS Mac.

    For example:
        1. Check if Android Studio is installed.
        2. Launch an Android Studio.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_file_name = 'studio'
        self._bin_folders = ['/Applications/Android Studio.app/Contents/MacOS']
        self._bin_paths = self._get_possible_bin_paths()
        self._init_installed_path(installed_path)


class IdeEclipse(IdeBase):
    """Class offers a set of Eclipse launching utilities.

    For example:
        1. Check if Eclipse is installed.
        2. Launch an Eclipse.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._ide_name = constant.IDE_ECLIPSE
        self._bin_file_name = 'eclipse*'

    def _get_script_from_system(self):
        """Get correct IDE installed path from internal path.

        Remove any file with extension, the filename should be like, 'eclipse',
        'eclipse47' and so on, check if the file is executable and filter out
        file such as 'eclipse.ini'.

        Returns:
            The sh full path, or None if no IntelliJ version is installed.
        """
        for ide_path in self._bin_paths:
            ls_output = glob.glob(ide_path, recursive=True)
            if ls_output:
                ls_output = sorted(ls_output)
                match_eclipses = []
                for path in ls_output:
                    if os.access(path, os.X_OK):
                        match_eclipses.append(path)
                if match_eclipses:
                    match_eclipses = sorted(match_eclipses)
                    logging.debug('Result for checking %s after sort: %s.',
                                  self._ide_name, match_eclipses[0])
                    return match_eclipses[0]
        logging.error('No %s installed.', self._ide_name)
        return None


class IdeLinuxEclipse(IdeEclipse):
    """Class offers a set of Eclipse launching utilities for OS Linux.

    For example:
        1. Check if Eclipse is installed.
        2. Launch an Eclipse.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_folders = ['/opt/eclipse*', '/usr/bin/']
        self._bin_paths = self._get_possible_bin_paths()
        self._init_installed_path(installed_path)


class IdeMacEclipse(IdeEclipse):
    """Class offers a set of Eclipse launching utilities for OS Mac.

    For example:
        1. Check if Eclipse is installed.
        2. Launch an Eclipse.
    """

    def __init__(self, installed_path=None, config_reset=False):
        super().__init__(installed_path, config_reset)
        self._bin_file_name = 'Eclipse.app'
        self._bin_folders = [os.path.expanduser('~/eclipse/**')]
        self._bin_paths = self._get_possible_bin_paths()
        self._init_installed_path(installed_path)

    def _get_ide_cmd(self, project_file):
        """Compose launch IDE command to run a new process and redirect output.

        Args:
            project_file: The full path of the IDE's project file.

        Returns:
            A string of launch IDE command.
        """
        return ' '.join([
            _NOHUP,
            'open',
            self._installed_path.replace(' ', r'\ '),
            os.path.dirname(project_file), _IGNORE_STD_OUT_ERR_CMD, '&'
        ])


def _get_script_from_internal_path(ide_paths, ide_name):
    """Get the studio.sh script path from internal path.

    Args:
        ide_paths: A list of IDE installed paths to be checked.
        ide_name: The IDE name.

    Returns:
        The IDE full path or None if no Android Studio or Eclipse is installed.
    """
    for ide_path in ide_paths:
        ls_output = glob.glob(ide_path, recursive=True)
        ls_output = sorted(ls_output)
        if ls_output:
            logging.debug('Result for checking %s after sort: %s.', ide_name,
                          ls_output[0])
            return ls_output[0]
    logging.error('No %s installed.', ide_name)
    return None


def _run_ide_sh(run_sh_cmd, project_path):
    """Run IDE launching script with an IntelliJ project path as argument.

    Args:
        run_sh_cmd: The command to launch IDE.
        project_path: The path of IntelliJ IDEA project content.
    """
    assert run_sh_cmd, 'No suitable IDE installed.'
    logging.debug('Run command: "%s" to launch project.', run_sh_cmd)
    try:
        subprocess.check_call(run_sh_cmd, shell=True)
    except subprocess.CalledProcessError as err:
        logging.error('Launch project path %s failed with error: %s.',
                      project_path, err)


def _walk_tree_find_ide_exe_file(top, ide_script_name):
    """Recursively descend the directory tree rooted at top and filter out the
       IDE executable script we need.

    Args:
        top: the tree root to be checked.
        ide_script_name: IDE file name such i.e. IdeIntelliJ._INTELLIJ_EXE_FILE.

    Returns:
        the IDE executable script file(s) found.
    """
    logging.info('Searching IDE script %s in path: %s.', ide_script_name, top)
    for root, _, files in os.walk(top):
        logging.debug('Search all files under %s to get %s, %s.', top, root,
                      files)
        for file_ in fnmatch.filter(files, ide_script_name):
            logging.debug('Use file name filter to find %s in path %s.', file_,
                          os.path.join(root, file_))
            yield os.path.join(root, file_)


def _get_run_ide_cmd(sh_path, project_file):
    """Get the command to launch IDE.

    Args:
        sh_path: The idea.sh path where IDE is installed.
        project_file: The path of IntelliJ IDEA project file.

    Returns:
        A string: The IDE launching command.
    """
    # In command usage, the space ' ' should be '\ ' for correctness.
    return ' '.join([
        _NOHUP,
        sh_path.replace(' ', r'\ '),
        project_file,
        _IGNORE_STD_OUT_ERR_CMD,
        '&'
    ])


def _get_script_from_file_path(input_path, ide_file_name):
    """Get IDE executable script file from input file path.

    Args:
        input_path: the file path to be checked.
        ide_file_name: the IDE executable script file name.

    Returns:
        An IDE executable script path if exists otherwise None.
    """
    if os.path.basename(input_path).startswith(ide_file_name):
        files_found = glob.glob(input_path)
        if files_found:
            return sorted(files_found)[0]
    return None


def _get_script_from_dir_path(input_path, ide_file_name):
    """Get an IDE executable script file from input directory path.

    Args:
        input_path: the directory to be searched.
        ide_file_name: the IDE executable script file name.

    Returns:
        An IDE executable script path if exists otherwise None.
    """
    logging.debug('Call _get_script_from_dir_path with %s, and %s', input_path,
                  ide_file_name)
    files_found = list(_walk_tree_find_ide_exe_file(input_path, ide_file_name))
    if files_found:
        return sorted(files_found)[0]
    return None


def _launch_ide(project_path, run_ide_cmd, ide_name):
    """Launches relative IDE by opening the passed project file.

    Args:
        project_path: The full path of the IDE project content.
        run_ide_cmd: The command to launch IDE.
        ide_name: the IDE name is to be launched.
    """
    assert project_path, 'Empty content path is not allowed.'
    logging.info('Launch %s for project content path: %s.', ide_name,
                 project_path)
    _run_ide_sh(run_ide_cmd, project_path)


def _is_intellij_project(project_path):
    """Checks if the path passed in is an IntelliJ project content.

    Args:
        project_path: The full path of IDEA project content, which contains
        .idea folder and .iml file(s).

    Returns:
        True if project_path is an IntelliJ project, False otherwise.
    """
    if not os.path.isfile(project_path):
        return os.path.isdir(project_path) and os.path.isdir(
            os.path.join(project_path, _IDEA_FOLDER))

    _, ext = os.path.splitext(os.path.basename(project_path))
    if ext and _IML_EXTENSION == ext.lower():
        path = os.path.dirname(project_path)
        logging.debug('Extracted path is: %s.', path)
        return os.path.isdir(os.path.join(path, _IDEA_FOLDER))
    return False


def _get_script_from_input_path(input_path, ide_file_name):
    """Get correct IntelliJ executable script path from input path.

    1. If input_path is a file, check if it is an IDE executable script file.
    2. It input_path is a directory, search if it contains IDE executable script
       file(s).

    Args:
        input_path: input path to be checked if it's an IDE executable
                    script.
        ide_file_name: the IDE executable script file name.

    Returns:
        IDE executable file(s) if exists otherwise None.
    """
    if not input_path:
        return None
    ide_path = ''
    if os.path.isfile(input_path):
        ide_path = _get_script_from_file_path(input_path, ide_file_name)
    if os.path.isdir(input_path):
        ide_path = _get_script_from_dir_path(input_path, ide_file_name)
    if ide_path:
        logging.debug('IDE installed path from user input: %s.', ide_path)
        return ide_path
    return None


def _get_intellij_version_path(version_path):
    """Locates the IntelliJ IDEA launch script path by version.

    Args:
        version_path: IntelliJ CE or UE version launch script path.

    Returns:
        The sh full path, or None if no such IntelliJ version is installed.
    """
    ls_output = glob.glob(version_path, recursive=True)
    if not ls_output:
        return None
    ls_output = sorted(ls_output, reverse=True)
    logging.debug('Result for checking IntelliJ path %s after sorting:%s.',
                  version_path, ls_output)
    return ls_output


def _ask_preference(all_versions):
    """Ask users which version they prefer.

    Args:
        all_versions: A list of all CE and UE version launch script paths.

    Returns:
        An users selected version.
    """
    options = []
    for i, sfile in enumerate(all_versions, 1):
        options.append('\t{}. {}'.format(i, sfile))
    query = ('You installed {} versions of IntelliJ:\n{}\nPlease select '
             'one.\t').format(len(all_versions), '\n'.join(options))
    return _select_intellij_version(query, all_versions)


def _select_intellij_version(query, all_versions):
    """Select one from different IntelliJ versions users installed.

    Args:
        query: The query message.
        all_versions: A list of all CE and UE version launch script paths.
    """
    all_numbers = []
    for i in range(len(all_versions)):
        all_numbers.append(str(i + 1))
    input_data = input(query)
    while not input_data in all_numbers:
        input_data = input('Please select a number:\t')
    return all_versions[int(input_data) - 1]


def _get_ide(installed_path=None, ide='j', config_reset=False, is_mac=False):
    """Get IDE to be launched according to the ide input and OS type.

    Args:
        installed_path: The IDE installed path to be checked.
        ide: A key character of IDE to be launched. Default ide='j' is to
            launch IntelliJ.
        config_reset: A boolean, if true reset configuration data.

    Returns:
        A corresponding IDE instance.
    """
    if is_mac:
        return _get_mac_ide(installed_path, ide, config_reset)
    return _get_linux_ide(installed_path, ide, config_reset)


def _get_mac_ide(installed_path=None, ide='j', config_reset=False):
    """Get IDE to be launched according to the ide input for OS Mac.

    Args:
        installed_path: The IDE installed path to be checked.
        ide: A key character of IDE to be launched. Default ide='j' is to
            launch IntelliJ.
        config_reset: A boolean, if true reset configuration data.

    Returns:
        A corresponding IDE instance.
    """
    if ide == 'e':
        return IdeMacEclipse(installed_path)
    if ide == 's':
        return IdeMacStudio(installed_path)
    return IdeMacIntelliJ(installed_path, config_reset)


def _get_linux_ide(installed_path=None, ide='j', config_reset=False):
    """Get IDE to be launched according to the ide input for OS Linux.

    Args:
        installed_path: The IDE installed path to be checked.
        ide: A key character of IDE to be launched. Default ide='j' is to
            launch IntelliJ.
        config_reset: A boolean, if true reset configuration data.

    Returns:
        A corresponding IDE instance.
    """
    if ide == 'e':
        return IdeLinuxEclipse(installed_path)
    if ide == 's':
        return IdeLinuxStudio(installed_path)
    return IdeLinuxIntelliJ(installed_path, config_reset)
