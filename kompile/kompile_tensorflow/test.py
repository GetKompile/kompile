from torchvision import datasets, transforms
from torch.utils.data import DataLoader

# Transform to normalized Tensors
transform = transforms.Compose([transforms.ToTensor(),
                                transforms.Normalize((0.1307,), (0.3081,))])

train_dataset = datasets.MNIST('./MNIST/', train=True, transform=transform, download=True)
# test_dataset = datasets.MNIST('./MNIST/', train=False, transform=transform, download=True)


train_loader = DataLoader(train_dataset, batch_size=len(train_dataset))
for data in train_loader:
    next2 = next(iter(train_loader))
    train_dataset_array = next2[0].numpy()
    print(train_dataset_array)