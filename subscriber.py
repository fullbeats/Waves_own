import sys
import zmq
import time
import json

class Subscriber():
    """
    example usage
    >>> data_class = Subscriber(ports=["5601","5602","5603"],topic="LTC_USDN")
    >>> data_stream = data_class.subscribe()
    >>> for data in data_stream:
    >>>    print(data)
    
    """
    def __init__(self,ports=["5600"],topic="ads",ip="192.168.20.101"):
        """
        example usage
        >>> data_class = Subscriber(ports=["5601","5602","5603"],topic="LTC_USDN")
        >>> data_stream = data_class.subscribe()
        >>> for data in data_stream:
        >>>    print(data)
        
       topic ALL gets all data from our addresses"""
        self.ports = ports
        self.topic = topic
        self.context = zmq.Context()
        self.socket = self.context.socket(zmq.SUB)
        for port in self.ports:
            self.socket.connect (f"tcp://{ip}:{port}")
    def subscribe(self, topics=False):
        """
        Subscribe to Multi Topics/Channels:
        >>> data_class = Subscriber(ports=["5601","5602","5603"])
        >>> data_stream = data_class.subscribe(topics=["LTC_USDN","WAVES_USDN"])
        >>> for data in data_stream:
        >>>     print(data)
        """
        
        # add topic list maybe later
        if not topics:
            topicfilter = self.topic.encode()
            self.socket.setsockopt(zmq.SUBSCRIBE, topicfilter)
            self.socket.setsockopt(zmq.RCVBUF, 200000)
            self.socket.setsockopt(zmq.RCVHWM, 0)
        else:
            for topic_ in topics:
                topicfilter = topic_.encode()
                self.socket.setsockopt(zmq.SUBSCRIBE, topicfilter)
        #flags=zmq.NOBLOCK
        # Process 5 updates
        while True:
            frameList = []
            # topic, json_data = self.socket.recv_multipart(track=True) 
            frameList = self.socket.recv_multipart(track=True) 
            print(len(frameList))
            for frame in frameList:
                if frame == b"ISR":
                    continue
                data = json.loads(frame.decode())



            if len(frameList) < 2:
                raise Exception("Only one frame received")
            else:
                topic = frameList[0].decode()

            if not topics:
                yield data
            else:
                yield topic , data
            # topic, messagedata = string.split()
            # print(time.time())q
            # print (string)
            #    
if __name__ == "__main__":
    i = 0 
    # data_class = Subscriber(ports=["3333"],ip="192.168.20.218",topic="ISR")
    data_class = Subscriber(ports=["6601"],ip="192.168.20.152",topic="Bittrex")
    data_stream = data_class.subscribe()
    for data in data_stream:
        print(data)
        