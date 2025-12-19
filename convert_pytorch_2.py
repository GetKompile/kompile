import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torchvision import datasets, transforms

from kompile_pytorch.convert import convert_pytorch


class CNN(nn.Module):
    def __init__(self):
        super(CNN, self).__init__()
        self.conv1 = nn.Sequential(         
            nn.Conv2d(
                in_channels=1,              
                out_channels=16,            
                kernel_size=5,              
                stride=1,                   
                padding=2,                  
            ),                              
            nn.ReLU(),                      
            nn.MaxPool2d(kernel_size=2),    
        )
        self.conv2 = nn.Sequential(         
            nn.Conv2d(16, 32, 5, 1, 2),     
            nn.ReLU(),                      
            nn.MaxPool2d(2),                
        )
        # fully connected layer, output 10 classes
        self.out = nn.Linear(32 * 7 * 7, 10)
    def forward(self, x):
        x = self.conv1(x)
        x = self.conv2(x)
        # flatten the output of conv2 to (batch_size, 32 * 7 * 7)
        x = x.view(x.size(0), -1)       
        output = self.out(x)
        output = F.log_softmax(output,dim=0)
        return output, x    # return x for visualization
clf = CNN()

train_loader = torch.utils.data.DataLoader(datasets.MNIST('../mnist_data',
                                                          download=True,
                                                          train=True,
                                                          transform=transforms.Compose([
                                                              transforms.ToTensor(), # first, convert image to PyTorch tensor
                                                              transforms.Normalize((0.1307,), (0.3081,)) # normalize inputs
                                                          ])),
                                           batch_size=10,
                                           shuffle=True)
# for data in train_loader:
#     print(data)

x = torch.from_numpy(np.ones((10,1,28,28), dtype=np.float32))
# ./kompile model convert --format=onnx --inputFile=/api/kompile_pytorch/tests/output_cnn_mnist.onnx
dynamic_axes = {'input.1':0}
convert_pytorch(clf,x,'output_cnn_mnist.onnx',dynamic_axes=dynamic_axes)
