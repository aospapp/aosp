/******************************************************************************
 *
 *  Copyright (C) 2011-2012 Broadcom Corporation
 *  Copyright 2018-2019 NXP.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "phNxpConfig.h"
#include <stdio.h>
#include <string>
#include <vector>
#include <list>
#include <sys/stat.h>
#include <android-base/stringprintf.h>
#include <base/logging.h>
#include <cutils/properties.h>
#include  "phNxpLog.h"

using android::base::StringPrintf;

extern bool uwb_debug_enabled;

#define nxp_config_name             "libuwb-nxp.conf"
#define uci_config_name             "libuwb-uci.conf"


const char alternative_config_path[] = "/vendor/etc/";

#define extra_config_base "libuwb-"
#define extra_config_ext ".conf"

#define extra_config_ext        ".conf"
#define     IsStringValue       0x80000000

const char nxp_uci_config_path[] = "/vendor/etc/libuwb-uci.conf";
const char default_nxp_config_path[] = "/vendor/etc/";

using namespace::std;

class uwbParam : public string
{
public:
    uwbParam();
    uwbParam(const char* name, const string& value);
    uwbParam(const char* name, unsigned long value);
    virtual ~uwbParam();
    unsigned long numValue() const {return m_numValue;}
    const char*   str_value() const {return m_str_value.c_str();}
    size_t        str_len() const   {return m_str_value.length();}
private:
    string          m_str_value;
    unsigned long   m_numValue;
};

class CUwbNxpConfig : public vector<const uwbParam*>
{
public:
    virtual ~CUwbNxpConfig();
    static CUwbNxpConfig& GetInstance();
    friend void readOptionalConfig(const char* optional);

    bool    getValue(const char* name, char* pValue, size_t len) const;
    bool    getValue(const char* name, unsigned long& rValue) const;
    bool    getValue(const char* name, unsigned short & rValue) const;
    bool    getValue(const char* name, char* pValue, long len,long* readlen) const;
    const uwbParam*    find(const char* p_name) const;
    void    readNxpConfig(const char* fileName) const;
    void    clean();
private:
    CUwbNxpConfig();
    bool    readConfig(const char* name, bool bResetContent);
    void    moveFromList();
    void    moveToList();
    void    add(const uwbParam* pParam);
    void    dump();
    bool    isAllowed(const char* name);
    list<const uwbParam*> m_list;
    bool    mValidFile;
    string  mCurrentFile;

    unsigned long   state;

    inline bool Is(unsigned long f) {return (state & f) == f;}
    inline void Set(unsigned long f) {state |= f;}
    inline void Reset(unsigned long f) {state &= ~f;}
};

/*******************************************************************************
**
** Function:    isPrintable()
**
** Description: determine if 'c' is printable
**
** Returns:     1, if printable, otherwise 0
**
*******************************************************************************/
inline bool isPrintable(char c)
{
    return  (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') ||
            c == '/' || c == '_' || c == '-' || c == '.';
}

/*******************************************************************************
**
** Function:    isDigit()
**
** Description: determine if 'c' is numeral digit
**
** Returns:     true, if numerical digit
**
*******************************************************************************/
inline bool isDigit(char c, int base)
{
    if ('0' <= c && c <= '9')
        return true;
    if (base == 16)
    {
        if (('A' <= c && c <= 'F') ||
            ('a' <= c && c <= 'f') )
            return true;
    }
    return false;
}

/*******************************************************************************
**
** Function:    getDigitValue()
**
** Description: return numerical value of a decimal or hex char
**
** Returns:     numerical value if decimal or hex char, otherwise 0
**
*******************************************************************************/
inline int getDigitValue(char c, int base)
{
    if ('0' <= c && c <= '9')
        return c - '0';
    if (base == 16)
    {
        if ('A' <= c && c <= 'F')
            return c - 'A' + 10;
        else if ('a' <= c && c <= 'f')
            return c - 'a' + 10;
    }
    return 0;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::readConfig()
**
** Description: read Config settings and parse them into a linked list
**              move the element from linked list to a array at the end
**
** Returns:     1, if there are any config data, 0 otherwise
**
*******************************************************************************/
bool CUwbNxpConfig::readConfig(const char* name, bool bResetContent)
{
    enum {
        BEGIN_LINE = 1,
        TOKEN,
        STR_VALUE,
        NUM_VALUE,
        BEGIN_HEX,
        BEGIN_QUOTE,
        END_LINE
    };

    FILE*   fd;
    struct stat buf;
    string  token;
    string  strValue;
    unsigned long    numValue = 0;
    uwbParam* pParam = NULL;
    int     i = 0;
    int     base = 0;
    char    c;
    int     bflag = 0;
    mCurrentFile = name;

    state = BEGIN_LINE;
    /* open config file, read it into a buffer */
    if ((fd = fopen(name, "rb")) == NULL)
    {
        DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s Cannot open config file %s\n", __func__, name);
        if (bResetContent)
        {
            DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s Using default value for all settings\n", __func__);
            mValidFile = false;
        }
        return false;
    }
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s Opened %s config %s\n", __func__, (bResetContent ? "base" : "optional"), name);
    if(stat(name, &buf) < 0)
    {
        DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("Get File Information failed");
        fclose(fd);
        return false;
    }

    mValidFile = true;
    if (size() > 0)
    {
        if (bResetContent)
        clean();
        else
            moveToList();
    }

    for (;;)
    {
        if (feof(fd) || fread(&c, 1, 1, fd) != 1)
        {
            if (state == BEGIN_LINE)
                break;

            // got to the EOF but not in BEGIN_LINE state so the file
            // probably does not end with a newline, so the parser has
            // not processed current line, simulate a newline in the file
            c = '\n';
        }

        switch (state & 0xff)
        {
        case BEGIN_LINE:
            if (c == '#')
                state = END_LINE;
            else if (isPrintable(c))
            {
                i = 0;
                token.erase();
                strValue.erase();
                state = TOKEN;
                token.push_back(c);
            }
            break;
        case TOKEN:
            if (c == '=')
            {
                token.push_back('\0');
                state = BEGIN_QUOTE;
            }
            else if (isPrintable(c))
                token.push_back(c);
            else
                state = END_LINE;
            break;
        case BEGIN_QUOTE:
            if (c == '"')
            {
                state = STR_VALUE;
                base = 0;
            }
            else if (c == '0')
                state = BEGIN_HEX;
            else if (isDigit(c, 10))
            {
                state = NUM_VALUE;
                base = 10;
                numValue = getDigitValue(c, base);
                i = 0;
            }
            else if (c == '{')
            {
                state = NUM_VALUE;
                bflag = 1;
                base = 16;
                i = 0;
                Set(IsStringValue);
            }
            else
                state = END_LINE;
            break;
        case BEGIN_HEX:
            if (c == 'x' || c == 'X')
            {
                state = NUM_VALUE;
                base = 16;
                numValue = 0;
                i = 0;
                break;
            }
            else if (isDigit(c, 10))
            {
                state = NUM_VALUE;
                base = 10;
                numValue = getDigitValue(c, base);
                break;
            }
            else if (c != '\n' && c != '\r')
            {
                state = END_LINE;
                break;
            }
            // fall through to numValue to handle numValue
            if (isDigit(c, base))
            {
                numValue *= base;
                numValue += getDigitValue(c, base);
                ++i;
            }
            //else if(bflag == 1 && (c == ' ' || c == '\r' || c=='\n' || c=='\t'))
            //{
            //    break;
            //}
            //else if (base == 16 && (c== ','|| c == ':' || c == '-' || c == ' ' || c == '}'))
            //{

            //    if( c=='}' )
            //    {
            //        bflag = 0;
            //    }
            //    if (i > 0)
            //    {
            //        int n = (i+1) / 2;
            //        while (n-- > 0)
            //        {
            //            numValue = numValue >> (n * 8);
            //            unsigned char c = (numValue)  & 0xFF;
            //            strValue.push_back(c);
            //        }
            //    }

            //    Set(IsStringValue);
            //    numValue = 0;
            //    i = 0;
            //}
            else
            {
                if (c == '\n' || c == '\r')
                {
                    if(bflag == 0 )
                    {
                        state = BEGIN_LINE;
                    }
                }
            //    else
            //    {
            //        if( bflag == 0)
            //        {
            //            state = END_LINE;
            //        }
            //    }
                if (Is(IsStringValue) && base == 16 && i > 0)
                {
                    int n = (i+1) / 2;
                    while (n-- > 0)
                        strValue.push_back(((numValue >> (n * 8))  & 0xFF));
                }
                if (strValue.length() > 0)
                    pParam = new uwbParam(token.c_str(), strValue);
                else
                    pParam = new uwbParam(token.c_str(), numValue);
                add(pParam);
                strValue.erase();
                numValue = 0;
            }
            break;

        case NUM_VALUE:
            if (isDigit(c, base))
            {
                numValue *= base;
                numValue += getDigitValue(c, base);
                ++i;
            }
            else if(bflag == 1 && (c == ' ' || c == '\r' || c=='\n' || c=='\t'))
            {
                break;
            }
            else if (base == 16 && (c== ','|| c == ':' || c == '-' || c == ' ' || c == '}'))
            {

                if( c=='}' )
                {
                    bflag = 0;
                }
                if (i > 0)
                {
                    int n = (i+1) / 2;
                    while (n-- > 0)
                    {
                        numValue = numValue >> (n * 8);
                        unsigned char c = (numValue)  & 0xFF;
                        strValue.push_back(c);
                    }
                }

                Set(IsStringValue);
                numValue = 0;
                i = 0;
            }
            else
            {
                if (c == '\n' || c == '\r')
                {
                    if(bflag == 0 )
                    {
                        state = BEGIN_LINE;
                    }
                }
                else
                {
                    if( bflag == 0)
                    {
                        state = END_LINE;
                    }
                }
                if (Is(IsStringValue) && base == 16 && i > 0)
                {
                    int n = (i+1) / 2;
                    while (n-- > 0)
                        strValue.push_back(((numValue >> (n * 8))  & 0xFF));
                }
                if (strValue.length() > 0)
                    pParam = new uwbParam(token.c_str(), strValue);
                else
                    pParam = new uwbParam(token.c_str(), numValue);
                add(pParam);
                strValue.erase();
                numValue = 0;
            }
            break;
        case STR_VALUE:
            if (c == '"')
            {
                strValue.push_back('\0');
                state = END_LINE;
                pParam = new uwbParam(token.c_str(), strValue);
                add(pParam);
            }
            else if (isPrintable(c))
                strValue.push_back(c);
            break;
        case END_LINE:
            if (c == '\n' || c == '\r')
                state = BEGIN_LINE;
            break;
        default:
            break;
        }
    }

    fclose(fd);

    moveFromList();
    return size() > 0;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::CUwbNxpConfig()
**
** Description: class constructor
**
** Returns:     none
**
*******************************************************************************/
CUwbNxpConfig::CUwbNxpConfig() :
    mValidFile(true),
    state(0)
{
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::~CUwbNxpConfig()
**
** Description: class destructor
**
** Returns:     none
**
*******************************************************************************/
CUwbNxpConfig::~CUwbNxpConfig()
{
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::GetInstance()
**
** Description: get class singleton object
**
** Returns:     none
**
*******************************************************************************/
CUwbNxpConfig& CUwbNxpConfig::GetInstance()
{
  static CUwbNxpConfig theInstance;
  if (theInstance.size() == 0 && theInstance.mValidFile) {
    string strPath;
    if (default_nxp_config_path[0] != '\0') {
      strPath.assign(default_nxp_config_path);
      strPath += nxp_config_name;
      theInstance.readConfig(strPath.c_str(), true);
      if (!theInstance.empty()) {
        return theInstance;
      }
    }
  }
  return theInstance;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::getValue()
**
** Description: get a string value of a setting
**
** Returns:     true if setting exists
**              false if setting does not exist
**
*******************************************************************************/
bool CUwbNxpConfig::getValue(const char* name, char* pValue, size_t len) const
{
    const uwbParam* pParam = find(name);
    if (pParam == NULL)
        return false;

    if (pParam->str_len() > 0)
    {
        memset(pValue, 0, len);
        memcpy(pValue, pParam->str_value(), pParam->str_len());
        return true;
    }
    return false;
}

bool CUwbNxpConfig::getValue(const char* name, char* pValue, long len,long* readlen) const
{
    const uwbParam* pParam = find(name);
    if (pParam == NULL)
        return false;

    if (pParam->str_len() > 0)
    {
        if(pParam->str_len() <= (unsigned long)len)
        {
            memset(pValue, 0, len);
            memcpy(pValue, pParam->str_value(), pParam->str_len());
            *readlen = pParam->str_len();
        }
        else
        {
            *readlen = -1;
        }

        return true;
    }
    return false;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::getValue()
**
** Description: get a long numerical value of a setting
**
** Returns:     true if setting exists
**              false if setting does not exist
**
*******************************************************************************/
bool CUwbNxpConfig::getValue(const char* name, unsigned long& rValue) const
{
    const uwbParam* pParam = find(name);
    if (pParam == NULL)
        return false;

    if (pParam->str_len() == 0)
    {
        rValue = static_cast<unsigned long>(pParam->numValue());
        return true;
    }
    return false;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::getValue()
**
** Description: get a short numerical value of a setting
**
** Returns:     true if setting exists
**              false if setting does not exist
**
*******************************************************************************/
bool CUwbNxpConfig::getValue(const char* name, unsigned short& rValue) const
{
    const uwbParam* pParam = find(name);
    if (pParam == NULL)
        return false;

    if (pParam->str_len() == 0)
    {
        rValue = static_cast<unsigned short>(pParam->numValue());
        return true;
    }
    return false;
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::find()
**
** Description: search if a setting exist in the setting array
**
** Returns:     pointer to the setting object
**
*******************************************************************************/
const uwbParam* CUwbNxpConfig::find(const char* p_name) const
{
    if (size() == 0)
        return NULL;

    for (const_iterator it = begin(), itEnd = end(); it != itEnd; ++it)
    {
        if (**it < p_name)
        {
            continue;
        }
        else if (**it == p_name)
        {
            if((*it)->str_len() > 0)
            {
                DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s found %s=%s\n", __func__, p_name, (*it)->str_value());
            }
            else
            {
                DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s found %s=(0x%lx)\n", __func__, p_name, (*it)->numValue());
            }
            return *it;
        }
        else
            break;
    }
    return NULL;
}
/*******************************************************************************
**
** Function:    CUwbNxpConfig::readNxpPHYConfig()
**
** Description: read Config settings from RF conf file
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::readNxpConfig(const char* fileName) const
{
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("readNxpConfig-Enter..Reading");
    CUwbNxpConfig::GetInstance().readConfig(fileName, false);
}
/*******************************************************************************
**
** Function:    CUwbNxpConfig::clean()
**
** Description: reset the setting array
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::clean()
{
    if (size() == 0)
        return;

    for (iterator it = begin(), itEnd = end(); it != itEnd; ++it)
        delete *it;
    clear();
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::Add()
**
** Description: add a setting object to the list
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::add(const uwbParam* pParam)
{
    if (m_list.size() == 0)
    {
        m_list.push_back(pParam);
        return;
    }
    if((mCurrentFile.find("nxpPhy") != std::string::npos) && !isAllowed(pParam->c_str()))
    {
        DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s Token restricted. Returning", __func__);
        return;
    }
    for (list<const uwbParam*>::iterator it = m_list.begin(), itEnd = m_list.end(); it != itEnd; ++it)
    {
        if (**it < pParam->c_str())
            continue;
        if (**it == pParam->c_str())
            m_list.insert(m_list.erase(it), pParam);
        else
            m_list.insert(it, pParam);

        return;
    }
    m_list.push_back(pParam);
}
/*******************************************************************************
**
** Function:    CUwbNxpConfig::dump()
**
** Description: prints all elements in the list
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::dump()
{
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s Enter", __func__);

    for (list<const uwbParam*>::iterator it = m_list.begin(), itEnd = m_list.end(); it != itEnd; ++it)
    {
        if((*it)->str_len()>0)
            DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s %s \t= %s", __func__, (*it)->c_str(),(*it)->str_value());
        else
            DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("%s %s \t= (0x%0lX)\n", __func__,(*it)->c_str(),(*it)->numValue());
    }
}
/*******************************************************************************
**
** Function:    CUwbNxpConfig::isAllowed()
**
** Description: checks if token update is allowed
**
** Returns:     true if allowed else false
**
*******************************************************************************/
bool CUwbNxpConfig::isAllowed(const char* name)
{
    string token(name);
    bool stat = false;
    return stat;
}
/*******************************************************************************
**
** Function:    CUwbNxpConfig::moveFromList()
**
** Description: move the setting object from list to array
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::moveFromList()
{
    if (m_list.size() == 0)
        return;

    for (list<const uwbParam*>::iterator it = m_list.begin(), itEnd = m_list.end(); it != itEnd; ++it)
        push_back(*it);
    m_list.clear();
}

/*******************************************************************************
**
** Function:    CUwbNxpConfig::moveToList()
**
** Description: move the setting object from array to list
**
** Returns:     none
**
*******************************************************************************/
void CUwbNxpConfig::moveToList()
{
    if (m_list.size() != 0)
        m_list.clear();

    for (iterator it = begin(), itEnd = end(); it != itEnd; ++it)
        m_list.push_back(*it);
    clear();
}

/*******************************************************************************
**
** Function:    uwbParam::uwbParam()
**
** Description: class constructor
**
** Returns:     none
**
*******************************************************************************/
uwbParam::uwbParam() :
    m_numValue(0)
{
}

/*******************************************************************************
**
** Function:    uwbParam::~uwbParam()
**
** Description: class destructor
**
** Returns:     none
**
*******************************************************************************/
uwbParam::~uwbParam()
{
}

/*******************************************************************************
**
** Function:    uwbParam::uwbParam()
**
** Description: class copy constructor
**
** Returns:     none
**
*******************************************************************************/
uwbParam::uwbParam(const char* name,  const string& value) :
    string(name),
    m_str_value(value),
    m_numValue(0)
{
}

/*******************************************************************************
**
** Function:    uwbParam::uwbParam()
**
** Description: class copy constructor
**
** Returns:     none
**
*******************************************************************************/
uwbParam::uwbParam(const char* name,  unsigned long value) :
    string(name),
    m_numValue(value)
{
}

/*******************************************************************************
**
** Function:    readOptionalConfig()
**
** Description: read Config settings from an optional conf file
**
** Returns:     none
**
*******************************************************************************/
void readOptionalConfig(const char* extra)
{
    string strPath;
    if (alternative_config_path[0] != '\0')
        strPath.assign(alternative_config_path);

    strPath += extra_config_base;
    strPath += extra;
    strPath += extra_config_ext;
    CUwbNxpConfig::GetInstance().readConfig(strPath.c_str(), false);
}

/*******************************************************************************
**
** Function:    GetStrValue
**
** Description: API function for getting a string value of a setting
**
** Returns:     True if found, otherwise False.
**
*******************************************************************************/
extern "C" int GetNxpConfigStrValue(const char* name, char* pValue, unsigned long len)
{
    CUwbNxpConfig& rConfig = CUwbNxpConfig::GetInstance();

    return rConfig.getValue(name, pValue, len);
}

/*******************************************************************************
**
** Function:    GetNxpConfigCountryCodeByteArrayValue()
**
** Description: Read byte array value from the config file.
**
** Parameters:
**              name    - name of the config param to read.
**              fName   - name of the Country code.
**              pValue  - pointer to input buffer.
**              bufflen - input buffer length.
**              len     - out parameter to return the number of bytes read from config file,
**                        return -1 in case bufflen is not enough.
**
** Returns:     TRUE[1] if config param name is found in the config file, else FALSE[0]
**
*******************************************************************************/
extern "C" int GetNxpConfigCountryCodeByteArrayValue(const char* name,const char* fName, char* pValue,long bufflen, long *len)
{
    DLOG_IF(INFO, true) << StringPrintf("GetNxpConfigCountryCodeByteArrayValue enter....");
    CUwbNxpConfig& rConfig = CUwbNxpConfig::GetInstance();
    readOptionalConfig(fName);
    DLOG_IF(INFO, true) << StringPrintf("GetNxpConfigCountryCodeByteArrayValue exit....");
    return rConfig.getValue(name, pValue, bufflen,len);
}

/*******************************************************************************
**
** Function:    GetNxpConfigUciByteArrayValue()
**
** Description: Read byte array value from the config file.
**
** Parameters:
**              name    - name of the config param to read.
**              pValue  - pointer to input buffer.
**              bufflen - input buffer length.
**              len     - out parameter to return the number of bytes read from config file,
**                        return -1 in case bufflen is not enough.
**
** Returns:     TRUE[1] if config param name is found in the config file, else FALSE[0]
**
*******************************************************************************/
extern "C" int GetNxpConfigUciByteArrayValue(const char* name, char* pValue,long bufflen, long *len)
{
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("GetNxpConfigUciByteArrayValue enter....");
    CUwbNxpConfig& rConfig = CUwbNxpConfig::GetInstance();
    rConfig.readNxpConfig(nxp_uci_config_path);
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("GetNxpConfigUciByteArrayValue exit....");
    return rConfig.getValue(name, pValue, bufflen,len);
}

/*******************************************************************************
**
** Function:    GetNxpByteArrayValue()
**
** Description: Read byte array value from the config file.
**
** Parameters:
**              name    - name of the config param to read.
**              pValue  - pointer to input buffer.
**              bufflen - input buffer length.
**              len     - out parameter to return the number of bytes read from config file,
**                        return -1 in case bufflen is not enough.
**
** Returns:     TRUE[1] if config param name is found in the config file, else FALSE[0]
**
*******************************************************************************/
extern "C" int GetNxpConfigByteArrayValue(const char* name, char* pValue,long bufflen, long *len)
{
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("GetNxpConfigByteArrayValue enter....");
    CUwbNxpConfig& rConfig = CUwbNxpConfig::GetInstance();
    rConfig.readNxpConfig(default_nxp_config_path);
    DLOG_IF(INFO, uwb_debug_enabled) << StringPrintf("GetNxpConfigByteArrayValue1 enter....");
    return rConfig.getValue(name, pValue, bufflen,len);
}

/*******************************************************************************
**
** Function:    GetNumValue
**
** Description: API function for getting a numerical value of a setting
**
** Returns:     true, if successful
**
*******************************************************************************/
extern "C" int GetNxpConfigNumValue(const char* name, void* pValue, unsigned long len)
{
    DLOG_IF(INFO, true) << StringPrintf("GetNxpConfigNumValue... enter....");
    if (pValue == NULL){
        return false;
    }
    CUwbNxpConfig& rConfig = CUwbNxpConfig::GetInstance();
    const uwbParam* pParam = rConfig.find(name);

    if (pParam == NULL)
        return false;
    unsigned long v = pParam->numValue();
    unsigned int strLen = (unsigned int)pParam->str_len();
    if (v == 0 && strLen > 0 && strLen < 4)
    {
        const unsigned char* p = (const unsigned char*)pParam->str_value();
        for (unsigned int i = 0 ; i < strLen; ++i)
        {
            v *= 256;
            v += *p++;
        }
    }

    switch (len)
    {
    case sizeof(unsigned long):
        *(static_cast<unsigned long*>(pValue)) = (unsigned long)v;
        break;
    case sizeof(unsigned short):
        *(static_cast<unsigned short*>(pValue)) = (unsigned short)v;
        break;
    case sizeof(unsigned char):
        *(static_cast<unsigned char*> (pValue)) = (unsigned char)v;
        break;
    default:
    DLOG_IF(INFO, true) << StringPrintf("GetNxpConfigNumValue default");
        return false;
    }
    return true;
}