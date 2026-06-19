import torch
from torch.utils.data import DataLoader
from torchvision import datasets, transforms

from kompile.pytorch.trainer import KompileTrainer
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
trainer = KompileTrainer(pipeline_path='train-pipeline.json ', variable_names=['input_1','label'])
trainer.fit(train_loader)
