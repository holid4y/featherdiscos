import importlib.util, socket, unittest
from unittest.mock import patch
from pathlib import Path
spec=importlib.util.spec_from_file_location('download',Path(__file__).parents[1]/'src/main/resources/download.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ProxyTests(unittest.TestCase):
    def test_non_public(self):
        for addr in ['127.0.0.1','10.0.0.1','169.254.169.254','192.168.1.103','100.105.145.99','::1','fc00::1','224.0.0.1','::ffff:127.0.0.1']:
            with self.subTest(addr=addr), self.assertRaises(ValueError):m.public_address(addr,80)
    def test_mixed_dns(self):
        rows=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443)),(socket.AF_INET,socket.SOCK_STREAM,6,'',('127.0.0.1',443))]
        with patch('socket.getaddrinfo',return_value=rows),self.assertRaises(ValueError):m.public_address('mixed.example',443)
    def test_port(self):
        with self.assertRaises(ValueError):m.public_address('example.com',22)
    def test_pins_address(self):
        rows=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443))]
        with patch('socket.getaddrinfo',return_value=rows) as dns,patch('socket.socket') as sock:
            m.public_address('example.com',443)
            dns.assert_called_once();sock.return_value.connect.assert_called_once_with(('8.8.8.8',443))
if __name__=='__main__':unittest.main()
