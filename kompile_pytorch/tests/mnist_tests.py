import unittest
import torch
from torch.utils.data import DataLoader
from torchvision import datasets, transforms
from kompile.pytorch.convert import convert_pytorch
from kompile.pytorch.trainer import KompileTrainer
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torchvision import datasets, transforms
from torch.autograd import Variable


class PytorchTestCase(unittest.TestCase):
    def test_mnist(self):
        train_loader = torch.utils.data.DataLoader(datasets.MNIST('../MNIST',
                                                                  download=True,
                                                                  train=True,
                                                                  transform=transforms.Compose([
                                                                      transforms.ToTensor(),
                                                                      # first, convert image to PyTorch tensor
                                                                      transforms.Normalize((0.1307,), (0.3081,))
                                                                      # normalize inputs
                                                                  ])),
                                                   batch_size=10,
                                                   shuffle=True)
        trainer = KompileTrainer(pipeline_path='', variable_names=[])
        trainer.fit(train_loader)


if __name__ == '__main__':
    unittest.main()
