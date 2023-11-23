#ifndef SESSION_LIBRARY_H_
#define SESSION_LIBRARY_H_

#include <mutex>
#include <vector>

namespace castkeydrm {

class SessionLibrary {
public:
    static SessionLibrary* get();
    std::vector<uint8_t> createSession();
    void closeSession(const std::vector<uint8_t>& session);

private:
    SessionLibrary() : next_session_id_(1) {}

    static std::mutex singleton_lock_;
    static SessionLibrary* singleton_;

    std::mutex session_lock_;
    uint32_t next_session_id_;
    std::vector<std::vector<uint8_t>> sessions_;
};

} // namespace castkeydrm

#endif // SESSION_LIBRARY_H_