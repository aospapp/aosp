# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import asyncio
import grpc
import logging

from avatar.bumble_server import utils
from bumble.core import ProtocolError
from bumble.device import Connection as BumbleConnection, Device, Peer
from bumble.gatt_client import CharacteristicProxy, ServiceProxy
from pandora_experimental.gatt_grpc_aio import GATTServicer
from pandora_experimental.gatt_pb2 import (
    SUCCESS,
    AttValue,
    ClearCacheRequest,
    ClearCacheResponse,
    DiscoverServiceByUuidRequest,
    DiscoverServicesRequest,
    DiscoverServicesResponse,
    ExchangeMTURequest,
    ExchangeMTUResponse,
    GattCharacteristic,
    GattCharacteristicDescriptor,
    GattService,
    ReadCharacteristicDescriptorRequest,
    ReadCharacteristicDescriptorResponse,
    ReadCharacteristicRequest,
    ReadCharacteristicResponse,
    ReadCharacteristicsFromUuidRequest,
    ReadCharacteristicsFromUuidResponse,
    WriteRequest,
    WriteResponse,
)
from typing import Dict, List


class GATTService(GATTServicer):
    device: Device
    peers: Dict[int, Peer]

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.device = device
        self.peers: Dict[int, Peer] = {}
        self.device.on('connection', self.on_connection)  # type: ignore
        self.device.on('disconnection', self.on_disconnection)  # type: ignore

    def __del__(self) -> None:
        self.device.remove_listener('connection', self.on_connection)  # type: ignore
        self.device.remove_listener('disconnection', self.on_disconnection)  # type: ignore

    def on_connection(self, connection: BumbleConnection) -> None:
        self.peers[connection.handle] = Peer(connection)  # type: ignore[no-untyped-call]

    def on_disconnection(self, connection: BumbleConnection) -> None:
        del self.peers[connection.handle]

    @utils.rpc
    async def ExchangeMTU(self, request: ExchangeMTURequest, context: grpc.ServicerContext) -> ExchangeMTUResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"ExchangeMTU: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        mtu = await peer.request_mtu(request.mtu)  # type: ignore
        assert mtu == request.mtu

        return ExchangeMTUResponse()

    @utils.rpc
    async def WriteAttFromHandle(self, request: WriteRequest, context: grpc.ServicerContext) -> WriteResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"WriteAttFromHandle: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        try:
            await peer.write_value(request.handle, request.value, with_response=True)  # type: ignore
            status = SUCCESS
        except ProtocolError as e:
            status = e.error_code

        return WriteResponse(handle=request.handle, status=status)

    @utils.rpc
    async def DiscoverServiceByUuid(
        self, request: DiscoverServiceByUuidRequest, context: grpc.ServicerContext
    ) -> DiscoverServicesResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"DiscoverServiceByUuid: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        services: List[ServiceProxy] = await peer.discover_service(request.uuid)  # type: ignore

        async def feed_service(service: ServiceProxy) -> None:
            characteristic: CharacteristicProxy
            for characteristic in await peer.discover_characteristics(service=service):  # type: ignore
                await characteristic.discover_descriptors()  # type: ignore[no-untyped-call]

        await asyncio.gather(*(feed_service(service) for service in services))

        return DiscoverServicesResponse(
            services=[
                GattService(
                    handle=service.handle,
                    type=int.from_bytes(bytes(service.type), 'little'),
                    uuid=service.uuid.to_hex_str('-'),  # type: ignore
                    characteristics=[
                        GattCharacteristic(
                            properties=characteristic.properties,  # type: ignore
                            permissions=0,  # TODO
                            uuid=characteristic.uuid.to_hex_str('-'),  # type: ignore
                            handle=characteristic.handle,  # type: ignore
                            descriptors=[
                                GattCharacteristicDescriptor(
                                    handle=descriptor.handle,  # type: ignore
                                    permissions=0,  # TODO
                                    uuid=str(descriptor.type),  # type: ignore
                                )
                                for descriptor in characteristic.descriptors  # type: ignore
                            ],
                        )
                        for characteristic in service.characteristics  # type: ignore
                    ],
                )
                for service in services
            ]
        )

    @utils.rpc
    async def DiscoverServices(
        self, request: DiscoverServicesRequest, context: grpc.ServicerContext
    ) -> DiscoverServicesResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"DiscoverServices: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        services: List[ServiceProxy] = await peer.discover_services()  # type: ignore

        async def feed_service(service: ServiceProxy) -> None:
            for characteristic in await peer.discover_characteristics(service=service):  # type: ignore
                await characteristic.discover_descriptors()  # type: ignore

        await asyncio.gather(*(feed_service(service) for service in services))

        return DiscoverServicesResponse(
            services=[
                GattService(
                    handle=service.handle,
                    type=int.from_bytes(bytes(service.type), 'little'),
                    uuid=service.uuid.to_hex_str('-'),  # type: ignore
                    characteristics=[
                        GattCharacteristic(
                            properties=characteristic.properties,  # type: ignore
                            permissions=0,  # TODO
                            uuid=characteristic.uuid.to_hex_str('-'),  # type: ignore
                            handle=characteristic.handle,  # type: ignore
                            descriptors=[
                                GattCharacteristicDescriptor(
                                    handle=descriptor.handle,  # type: ignore
                                    permissions=0,  # TODO
                                    uuid=str(descriptor.type),  # type: ignore
                                )
                                for descriptor in characteristic.descriptors  # type: ignore
                            ],
                        )
                        for characteristic in service.characteristics  # type: ignore
                    ],
                )
                for service in services
            ]
        )

    # TODO: implement `DiscoverServicesSdp`

    @utils.rpc
    async def ClearCache(self, request: ClearCacheRequest, context: grpc.ServicerContext) -> ClearCacheResponse:
        logging.info("ClearCache")
        return ClearCacheResponse()

    @utils.rpc
    async def ReadCharacteristicFromHandle(
        self, request: ReadCharacteristicRequest, context: grpc.ServicerContext
    ) -> ReadCharacteristicResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"ReadCharacteristicFromHandle: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        try:
            value = await peer.read_value(request.handle)  # type: ignore
            status = SUCCESS
        except ProtocolError as e:
            value = bytes()
            status = e.error_code

        return ReadCharacteristicResponse(value=AttValue(value=value), status=status)

    @utils.rpc
    async def ReadCharacteristicsFromUuid(
        self, request: ReadCharacteristicsFromUuidRequest, context: grpc.ServicerContext
    ) -> ReadCharacteristicsFromUuidResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"ReadCharacteristicsFromUuid: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        service_mock = type('', (), {'handle': request.start_handle, 'end_group_handle': request.end_handle})()

        try:
            characteristics = await peer.read_characteristics_by_uuid(request.uuid, service_mock)  # type: ignore

            return ReadCharacteristicsFromUuidResponse(
                characteristics_read=[
                    ReadCharacteristicResponse(
                        value=AttValue(value=value, handle=handle),  # type: ignore
                        status=SUCCESS,
                    )
                    for handle, value in characteristics  # type: ignore
                ]
            )

        except ProtocolError as e:
            return ReadCharacteristicsFromUuidResponse(
                characteristics_read=[ReadCharacteristicResponse(status=e.error_code)]
            )

    @utils.rpc
    async def ReadCharacteristicDescriptorFromHandle(
        self, request: ReadCharacteristicDescriptorRequest, context: grpc.ServicerContext
    ) -> ReadCharacteristicDescriptorResponse:
        connection_handle = int.from_bytes(request.connection.cookie.value, 'big')
        logging.info(f"ReadCharacteristicDescriptorFromHandle: {connection_handle}")

        connection = self.device.lookup_connection(connection_handle)
        assert connection
        peer = self.peers[connection.handle]

        try:
            value = await peer.read_value(request.handle)  # type: ignore
            status = SUCCESS
        except ProtocolError as e:
            value = bytes()
            status = e.error_code

        return ReadCharacteristicDescriptorResponse(value=AttValue(value=value), status=status)
