#include "SessionLibrary.h"

#include <algorithm>
#include <string>

namespace castkeydrm {

std::mutex SessionLibrary::singleton_lock_;
SessionLibrary* SessionLibrary::singleton_ = NULL;

SessionLibrary* SessionLibrary::get() {
    std::lock_guard<std::mutex> guard(singleton_lock_);

    if (singleton_ == NULL) {
        singleton_ = new SessionLibrary();
    }

    return singleton_;
}

std::vector<uint8_t> SessionLibrary::createSession() {
    std::lock_guard<std::mutex> guard(session_lock_);

    std::string session_string = std::to_string(next_session_id_);
    next_session_id_ += 1;
    sessions_.emplace_back(session_string.begin(), session_string.end());
    return sessions_.back();
}

void SessionLibrary::closeSession(const std::vector<uint8_t>& session) {
    std::lock_guard<std::mutex> guard(session_lock_);
    std::remove_if(sessions_.begin(), sessions_.end(),
                   [&session](const std::vector<uint8_t>& e) {
                     return std::equal(e.begin(), e.end(), session.begin());
                   });
}

} // namespace castkeydrm