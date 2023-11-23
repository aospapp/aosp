# TODO(hmtuan): add type annotation.
import requests
import time

class ImsApiConnector():
    """A wrapper class for Keysight Ims API Connector.

    Keysight provided an API connector application
    which is a HTTP server running on the same host
    as Keysight IMS server simulator and client simulator.
    It allows IMS simulator/app to be controlled via HTTP request.

    Attributes:
        api_connector_ip: ip of http server.
        api_connector_port: port of http server.
        ims_app: type of ims app (client/server).
        api_token: an arbitrary and unique token-string
            to identify the link between API connector
            and ims app.
        log: logger object.
    """

    def __init__(self, api_connector_ip,
                 api_connector_port, ims_app,
                 api_token, ims_app_ip,
                 ims_app_port, log):
        # api connector info
        self.api_connector_ip = api_connector_ip
        self.api_connector_port = api_connector_port

        # ims app info
        self.ims_app = ims_app
        self.api_token = api_token
        self.ims_app_ip = ims_app_ip
        self.ims_app_port = ims_app_port

        self.log = log
        # construct base url
        self.base_url = 'http://{addr}:{port}/ims/api/{app}s/{api_token}'.format(
            addr = self.api_connector_ip,
            port = self.api_connector_port,
            app = self.ims_app,
            api_token = self.api_token
        )

    def get_base_url(self):
        return self.base_url

    def create_ims_app_link(self):
        """Create link between Keysight API Connector to ims app."""
        self.log.info('Create ims app link: token:ip:port')
        self.log.info('Creating ims_{app} link: {token}:{target_ip}:{target_port}'.format(
            app = self.ims_app,
            token = self.api_token,
            target_ip = self.ims_app_ip,
            target_port= self.ims_app_port)
        )

        request_data = {
            "targetIpAddress": self.ims_app_ip,
            "targetWcfPort": self.ims_app_port
        }
        self.log.debug(f'Payload to create ims app link: {request_data}')
        r = requests.post(url = self.get_base_url(), json = request_data)

        self.log.info('HTTP request sent:')
        self.log.info('-> method: ' + str(r.request.method))
        self.log.info('-> url: ' + str(r.url))
        self.log.info('-> status_code: ' + str(r.status_code))

        return (r.status_code == 201)

    def remove_ims_app_link(self):
        """Remove link between Keysight API Connector to ims app."""
        self.log.info('Remove ims_{app} link: {token}'.format(
            app = self.ims_app,
            token = self.api_token)
        )

        r = requests.delete(url = self.get_base_url())

        self.log.info('-> method: ' + str(r.request.method))
        self.log.info('-> url: ' + str(r.url))
        self.log.info('-> status_code: ' + str(r.status_code))

        return (r.status_code == 200)

    def get_ims_app_property(self, property_name):
        """Get property value of IMS app.

        Attributes:
            property_name: name of property to get value.
        """
        self.log.info('Getting ims app property: ' + property_name)

        request_url = self.get_base_url() + '/get_property'
        request_params = {"propertyName": property_name}
        r = requests.get(url = request_url, params = request_params)

        self.log.info('-> method: ' + str(r.request.method))
        self.log.info('-> url: ' + str(r.url))
        self.log.info('-> status_code: ' + str(r.status_code))

        try:
            res_json = r.json()
        except:
            res_json = {'propertyValue': None }
        prop_value = res_json['propertyValue']

        return prop_value

    def set_ims_app_property(self, property_name, property_value):
        """Set property value of IMS app.

        Attributes:
            property_name: name of property to set value.
            property_value: value to be set.
        """
        self.log.info('Setting ims property: ' + property_name + ' = ' + str(property_value))

        request_url = self.get_base_url() + '/set_property'
        data = {
            'propertyName': property_name,
            'propertyValue': property_value
        }
        r = requests.post(url = request_url, json = data)

        self.log.info('-> method: ' + str(r.request.method))
        self.log.info('-> url: ' + str(r.url))
        self.log.info('-> status_code: ' + str(r.status_code))

        return (r.status_code == 200)

    def ims_api_call_method(self, method_name, method_args=None):
        """
        Attributes:
            method_name: a name of method from Keysight API in string.
            method_args: a python-array contains
                arguments for the called API method.
        Returns:
            a tuple of (STATUS_BOOL, FUNC_RET_VAL),
            if STATUS_BOOL is false, FUNC_RET_VAL is questionable/undefined,
            if STATUS_BOOL is true, FUNC_RET_VAL will be the API function return value
            or FUNC_RET_VAL is None if the called method return nothing.
        """
        self.log.info('Calling ims method: ' + method_name)

        if (method_args == None):
            method_args = []
        elif (type(method_args) != list):
            method_args = [method_args]
        data = {
            'methodName': method_name,
            'arguments': method_args
        }
        request_url = self.get_base_url() + '/call_method'
        r = requests.post(url = request_url, json = data)

        ret_val = None

        if ( ('Content-Type' in r.headers.keys()) and r.headers['Content-Type'] == 'application/json'):
            # TODO(hmtuan): try json.loads() instead
            response_body = r.json()
            if ((response_body != None) and ('returnValue' in response_body.keys())) :
                ret_val = response_body['returnValue']

        self.log.info('-> method: ' + str(r.request.method))
        self.log.info('-> url: ' + str(r.url))
        self.log.info('-> status_code: ' + str(r.status_code))
        self.log.info('-> ret_val: ' + str(ret_val))

        return (r.status_code == 200), ret_val

    def _is_line_idle(self, call_line_number):
        is_line_idle_prop = self.get_ims_app_property(
            f'IVoip.CallLineParams({call_line_number}).SessionState')
        return is_line_idle_prop == 'Idle'

    def _is_ims_client_app_registered(self):
        is_registered_prop = self.get_ims_app_property('IComponentControl.IsRegistered')
        return is_registered_prop == 'True'

    def initiate_call(self, callee_number, call_line_idx=0):
        """Dial to callee_number.

        Attributes:
            callee_number: number to be dialed to.
        """
        # create IMS-Client API link
        ret_val = self.create_ims_app_link()

        if not ret_val:
            raise RuntimeError('Fail to create link to IMS app.')

        # check if IMS-Client is registered, and if not, request client to perform Registration
        self.log.info('Ensuring client registered.')
        is_registered = self._is_ims_client_app_registered()
        if not is_registered:
            self.log.info('Client not currently registered - registering.')
            self.ims_api_call_method('ISipConnection.Register()')

        is_registered = self._is_ims_client_app_registered()
        if not is_registered:
            raise RuntimeError('Failed to register IMS-client to IMS-server.')

        # switch to call-line #1 (idx = 0)
        self.log.info('Switching to call-line #1.')
        self.set_ims_app_property('IVoip.SelectedCallLine', call_line_idx)

        # check whether the call-line #1 is ready for dialling
        is_line1_idle = self._is_line_idle(call_line_idx)
        if not is_line1_idle:
            raise RuntimeError('Call-line not is not in indle state.')

        # entering callee number for call-line #1
        self.log.info(f'Enter callee number: {callee_number}.')
        self.set_ims_app_property('IVoip.CallLineParams(0).CallLocation', callee_number)

        # dial entered callee number
        self.log.info('Dialling call.')
        self.ims_api_call_method('IVoip.Dial()')

        time.sleep(5)

        # check if dial success (not idle)
        is_line1_idle = self._is_line_idle(call_line_idx)
        if is_line1_idle:
            raise RuntimeError('Fail to dial.')
