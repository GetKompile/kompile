# kompile_utils
Utility and Datasets to facilitate working with Pytorch dataset


### usage

```python
from kompile_pytorch import *

trainingdata = datasets.MNIST(
    root="data",
    train=True,
    download=True
)
print(trainingdata[3])

traindl = utils.DataLoader(trainingdata, batch_size=4, shuffle=True)

# Display image and label.
trainfeature, trainlabel = next(iter(traindl))

print(trainfeature.shape)
print(trainlabel.shape)

print(type(trainfeature))
print(trainlabel.shape)
```