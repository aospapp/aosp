

#include <cstdint>

#include "stack/include/sdp_api.h"
#include "types/bluetooth/uuid.h"

namespace test {
namespace mock {
namespace stack_sdp_legacy {

bluetooth::legacy::stack::sdp::tSdpApi api_ = {
    .service =
        {
            .SDP_InitDiscoveryDb = [](tSDP_DISCOVERY_DB*, uint32_t, uint16_t,
                                      const bluetooth::Uuid*, uint16_t,
                                      const uint16_t*) -> bool {
              return false;
            },
            .SDP_CancelServiceSearch = nullptr,
            .SDP_ServiceSearchRequest = nullptr,
            .SDP_ServiceSearchAttributeRequest = nullptr,
            .SDP_ServiceSearchAttributeRequest2 = nullptr,
        },
    .db =
        {
            .SDP_FindServiceInDb = nullptr,
            .SDP_FindServiceUUIDInDb = nullptr,
            .SDP_FindServiceInDb_128bit = nullptr,
        },
    .record =
        {
            .SDP_FindAttributeInRec = nullptr,
            .SDP_FindServiceUUIDInRec_128bit = nullptr,
            .SDP_FindProtocolListElemInRec = nullptr,
            .SDP_FindProfileVersionInRec = nullptr,
            .SDP_FindServiceUUIDInRec = nullptr,
        },
    .handle =
        {
            .SDP_CreateRecord = nullptr,
            .SDP_DeleteRecord = nullptr,
            .SDP_AddAttribute = nullptr,
            .SDP_AddSequence = nullptr,
            .SDP_AddUuidSequence = nullptr,
            .SDP_AddProtocolList = nullptr,
            .SDP_AddAdditionProtoLists = nullptr,
            .SDP_AddProfileDescriptorList = nullptr,
            .SDP_AddLanguageBaseAttrIDList = nullptr,
            .SDP_AddServiceClassIdList = nullptr,
            .SDP_DeleteAttribute = nullptr,
        },
    .device_id =
        {
            .SDP_SetLocalDiRecord = nullptr,
            .SDP_DiDiscover = nullptr,
            .SDP_GetNumDiRecords = nullptr,
            .SDP_GetDiRecord = nullptr,
        },
};

}  // namespace stack_sdp_legacy
}  // namespace mock
}  // namespace test

const struct bluetooth::legacy::stack::sdp::tSdpApi*
bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api() {
  return &test::mock::stack_sdp_legacy::api_;
}
