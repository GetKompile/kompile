from PIL import Image
import time

import torch
import torchvision
from torch.utils.data import Dataset, DataLoader
from torchvision import models, transforms
import torch.nn as nn
import torch.optim as optim
from torch.optim import lr_scheduler
import torch.nn.functional as F
from torch.autograd import Variable


device = torch.device("cpu")
human_readable_labels = {
    'ageRange' : ['12-17', '18-23', '24-30', '31-40', '41-50', '51-60', '61+'],
    "gender": ["Female", "Male"],
    "race": ["Chinese", "Indian", "Malay", "Others"]
}
imsize = 224
loader = transforms.Compose([transforms.Resize((imsize, imsize)),
                                transforms.Grayscale(num_output_channels=3),
                                transforms.ToTensor(),
                                transforms.Normalize(mean = (0.4435,0.4435,0.4435), std=(0.2712,0.2712,0.2712)) ]) # stats from our data])

class Resnet50(nn.Module):
    def __init__(self, ageRange_Nodes=7, gender_Nodes=2, race_Nodes=4):
        super().__init__()

        self.ageRange_Nodes = ageRange_Nodes
        self.gender_Nodes = gender_Nodes
        self.race_Nodes = race_Nodes

        # using resnet50 as the backbone model
        resnet50 = models.resnet50(pretrained=True)
        num_features = resnet50.fc.in_features
        resnet50.fc = nn.Linear(num_features, 512)

        self.backbone_model = resnet50
        self.x1 = nn.Linear(512,
                            256)  # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x1.weight)

        self.x2 = nn.Linear(512,
                            256)  # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x2.weight)

        self.x3 = nn.Linear(512,
                            256)  # number of output nodes from backbone model (input nodes of fc layer in the ori model)
        nn.init.xavier_normal_(self.x3.weight)

        self.bn = nn.BatchNorm1d(512)  # dense layer batchnorm, just now is 2d for conv one

        # heads
        self.y_ageRange = nn.Linear(256, self.ageRange_Nodes)
        nn.init.xavier_normal_(self.y_ageRange.weight)
        self.y_gender = nn.Linear(256, self.gender_Nodes)
        nn.init.xavier_normal_(self.y_gender.weight)
        self.y_race = nn.Linear(256, self.race_Nodes)
        nn.init.xavier_normal_(self.y_race.weight)

    def forward(self, x):
        x = self.backbone_model(x)
        x = self.bn(x)
        x = F.relu(x)

        x1_output = self.x1(x)
        x2_output = self.x2(x)
        x3_output = self.x3(x)

        y_ageRange = self.y_ageRange(x1_output)
        y_gender = self.y_gender(x2_output)
        y_race = self.y_race(x3_output)

        return y_ageRange, y_gender, y_race


# Initialize model
PATH = "agerace_v2.pt"
model = Resnet50(7, 2, 4)
model.load_state_dict(torch.load(PATH, map_location='cpu'))
model.eval()
torch.no_grad()


def predictFace(img):
    # Pre-process image 
    image = loader(img).float()  # convert to PIL image format  
    image = Variable(image, requires_grad=False)
    image = image.unsqueeze(0)  #this is for VGG, may not be needed for ResNet
    image = image.to(device)
    model.to(device)

    # Prediction
    y_pred = model(image)

    # Prediction to readable format
    ageRange_pred = torch.argmax(y_pred[0].cpu(), dim=1)
    gender_pred = torch.argmax(y_pred[1].cpu(), dim=1)
    race_pred = torch.argmax(y_pred[2].cpu(), dim=1)
   # print("Prediction: ", human_readable_labels["ageRange"][ageRange_pred.item()], "; ", human_readable_labels["gender"][gender_pred.item()], "; ", human_readable_labels["race"][race_pred.item()])
    label = human_readable_labels["ageRange"][ageRange_pred.item()], human_readable_labels["gender"][gender_pred.item()], human_readable_labels["race"][race_pred.item()]
   
    return label

if __name__ == "__main__":
    listResult = []
    # Image path here
    image = Image.open('faceDet.png')
    # Start time 
    for x in range(5):
       start_time = time.time()
       labelResult = predictFace(image)
       print(labelResult)
       print("Execution time is {} seconds".format(time.time() - start_time))
       listResult.append(time.time() - start_time)
    print("Mean time is {}".format(sum(listResult) / len(listResult)))
